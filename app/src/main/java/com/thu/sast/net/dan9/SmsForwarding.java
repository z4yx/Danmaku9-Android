package com.thu.sast.net.dan9;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

/**
 * Created by zhang on 14-11-3.
 */
public class SmsForwarding implements Runnable {

    public static final int EVENT_STOPPED = 0;
    public static final int EVENT_SMS_FORWARDED = 1;
    public static final int EVENT_SMS_HTTP_FAILED = 2;

    Object waitLock = new Object();
    Boolean mRunning = true;
    boolean mFromLastSent = false, mDirtyFlag = false;
    Context mContext;
    Handler mHandler;
    URL mDan9Server;
    String mSmsPrefix;
    final Uri SMS_URI_INBOX = Uri.parse("content://sms/inbox");
    final String[] projection = new String[] { "_id", "address", "body"};
    int totalProcessed = 0;

    @Override
    public void run() {
        try {
            doForwarding();
        }catch (Exception e){

        }
        Log.w("SmsForwarding", "Stopped");
        mHandler.sendEmptyMessage(EVENT_STOPPED);
    }

    static class SmsInfo{
        int id;
        String from;
        String body;

        SmsInfo(int id, String from, String body) {
            this.id = id;
            this.from = from;
            this.body = body;
        }
    }

    SmsForwarding(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    public void setDan9Server(URL mDan9Server) {
        this.mDan9Server = mDan9Server;
    }

    public void setSmsPrefix(String mSmsPrefix) throws Exception{
        if(mSmsPrefix == null || mSmsPrefix.isEmpty())
            throw new Exception();
        this.mSmsPrefix = mSmsPrefix.toLowerCase();
    }

    public void WakeUp()
    {
        synchronized (waitLock) {
            mDirtyFlag = true;
            waitLock.notify();
        }
    }

    public void Start(boolean fromLastSent)
    {
        mFromLastSent = fromLastSent;
        setRunning(true);
        new Thread(this).start();
        Log.i("SmsForwarding", "Started");
    }

    public void Stop()
    {
        setRunning(false);
        WakeUp();
    }

    ArrayList<SmsInfo> getSmsFromInbox(int id){
        ArrayList<SmsInfo> ret = null;
        Cursor cursor = mContext.getContentResolver().query(SMS_URI_INBOX, projection, "_id>"+id, null, null);
        if(cursor.moveToFirst()){

            ret = new ArrayList<SmsInfo>(cursor.getCount());

            int index_Address = cursor.getColumnIndex("address");
            int index_Id = cursor.getColumnIndex("_id");
            int index_Body = cursor.getColumnIndex("body");

            do{
                int Id = cursor.getInt(index_Id);
                String strAddress = cursor.getString(index_Address);
                String strbody = cursor.getString(index_Body);

                Log.v("getSmsFromInbox", "SMS: " + Id + "," + strAddress + ',' + strbody);
                ret.add(new SmsInfo(Id, strAddress, strbody));
            }while(cursor.moveToNext());

        }else{
            Log.w("getSmsFromInbox", "Empty");
        }
        if(!cursor.isClosed()){
            cursor.close();
        }

        return ret;
    }

    private int getLatestSms() {
        int max_id=-1;
        try {
            Cursor cursor = mContext.getContentResolver().query(SMS_URI_INBOX, new String[]{"MAX(_id)"}, null, null, null);
            if (cursor.moveToFirst()) {
                max_id = cursor.getInt(0);
                Log.d("getLatestSms", "max SMS id: " + max_id);
            }
            if (!cursor.isClosed()) {
                cursor.close();
            }
        }catch (Exception e){
            Log.e("getLatestSms", "Exception:"+e.getMessage());
        }
        return max_id;
    }

    boolean postToServer(String json){
        HttpURLConnection conn = null;
        boolean result;
        try {
            byte[] body = json.getBytes();
            conn = (HttpURLConnection) mDan9Server.openConnection();
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);

            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            if(code != 200){
                Log.e("postToServer", "response code: "+code);
                throw new Exception("HTTP:" + conn.getResponseMessage());
            }
            Log.v("postToServer", "response code: "+code);
            result = true;
        }catch (Exception e){
            e.printStackTrace();
            result = false;
        }finally {
            try {
                conn.disconnect();
            }catch (Exception e){

            }
        }
        return result;
    }

    public void setRunning(boolean running){
        mRunning = running;
    }

    int readLatestSent()
    {
        return mContext.getSharedPreferences("SmsForwarding", Context.MODE_PRIVATE)
                .getInt("stored_latest", 0);
    }

    void saveLatestSent(int l)
    {
        SharedPreferences.Editor pref = mContext.getSharedPreferences("SmsForwarding", Context.MODE_PRIVATE).edit();
        pref.putInt("stored_latest", l);
        pref.commit();
    }

    void doForwarding()
    {
        int latest;
        if(mFromLastSent)
            latest = readLatestSent();
        else
            latest = getLatestSms();
        do{
            ArrayList<SmsInfo> SmsArray = getSmsFromInbox(latest);
            if(SmsArray == null){
                try {
                    synchronized (waitLock) {
                        if(mDirtyFlag)
                            waitLock.wait(500);
                        else
                            waitLock.wait(10000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Log.d("doForwarding", "wake");
                continue;
            }
            int max_id = 0;
            JSONArray json = new JSONArray();
            for(SmsInfo sms : SmsArray){
                if(sms.id > max_id)
                    max_id = sms.id;
                if(!sms.body.toLowerCase().startsWith(mSmsPrefix)
                        || sms.body.length() < 1+mSmsPrefix.length()){
                    continue;
                }
                if(sms.body.charAt(mSmsPrefix.length()) == '+'){
                    sms.body = sms.body.substring(mSmsPrefix.length()+1);
                }else{
                    sms.body = sms.body.substring(mSmsPrefix.length());
                }
                if(sms.body.isEmpty())
                    continue;

                JSONObject obj = new JSONObject();
                try {
                    obj.put("name", sms.from);
                    obj.put("m", sms.body);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                json.put(obj);
            }
            if(json.length()==0 || postToServer(json.toString())){
                latest = max_id;
                mDirtyFlag = false;
                saveLatestSent(latest);
                totalProcessed+=SmsArray.size();
                Log.v("doForwarding", "Processed: " + totalProcessed);
                mHandler.obtainMessage(EVENT_SMS_FORWARDED, totalProcessed, max_id).sendToTarget();
            }else{
                mHandler.obtainMessage(EVENT_SMS_HTTP_FAILED).sendToTarget();
            }
        }while (mRunning);
    }
}
