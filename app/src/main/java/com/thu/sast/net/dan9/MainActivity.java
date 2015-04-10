package com.thu.sast.net.dan9;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.net.MalformedURLException;
import java.net.URL;


public class MainActivity extends ActionBarActivity implements View.OnClickListener {

    static final String SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED";

    EditText mEditServer, mEditPrefix;
    Button mStartStopBtn;
    ImageButton mQRcodeBtn;
    SmsForwarding mSmsForwarding;
    boolean mIsThreadRunning;

    BroadcastReceiver mSmsReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (SMS_RECEIVED_ACTION.equals(intent.getAction())){
                Bundle bundle = intent.getExtras();
                if (bundle != null){
                    Object[] objArray = (Object[]) bundle.get("pdus");
                    Log.d("SmsBroadcastReceiver", "Got " + objArray.length + " messages.");
                    if(mSmsForwarding != null)
                        mSmsForwarding.WakeUp();
                }
            }
        }
    };

    Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            switch (message.what){
                case SmsForwarding.EVENT_STOPPED:
                    setRunStateUI(false);
                    break;
                case SmsForwarding.EVENT_SMS_FORWARDED:
                    setProcessedUI(message.arg1);
                    break;
            }
            return true;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSmsForwarding = new SmsForwarding(getApplicationContext(), mHandler);

        registerReceiver(mSmsReceiver, new IntentFilter(SMS_RECEIVED_ACTION));

        mStartStopBtn = (Button)findViewById(R.id.start_stop);
        mQRcodeBtn = (ImageButton)findViewById(R.id.qrcodeBtn);
        mEditPrefix = (EditText)findViewById(R.id.editPrefix);
        mEditServer = (EditText)findViewById(R.id.editServer);
        mStartStopBtn.setOnClickListener(this);
        mQRcodeBtn.setOnClickListener(this);
        mEditServer.setText(serverUrlPreference(null));
    }

    void setRunStateUI(boolean running){
        mQRcodeBtn.setEnabled(!running);
        mEditPrefix.setEnabled(!running);
        mEditServer.setEnabled(!running);
        ((TextView)findViewById(R.id.textState)).setText(running ? "运行中" : "已停止");
        ((Button)findViewById(R.id.start_stop)).setText(running ? "停止" : "启动");
        mIsThreadRunning = running;
    }

    void setProcessedUI(int count){
        ((TextView)findViewById(R.id.textProcessed)).setText("" + count);
    }

    String serverUrlPreference(String updated)
    {

        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        if(updated != null) {
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString("server_url", updated);
            editor.commit();
        }
        return sharedPref.getString("server_url", "");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onClick(View view) {
        if(view.getId() == mStartStopBtn.getId()) {
            if (mIsThreadRunning)
                mSmsForwarding.Stop();
            else {
                try {
                    mSmsForwarding.setDan9Server(new URL(mEditServer.getText().toString()));
                } catch (MalformedURLException e) {
                    Toast.makeText(this, "无效的服务器地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    mSmsForwarding.setSmsPrefix(mEditPrefix.getText().toString());
                } catch (Exception e) {
                    Toast.makeText(this, "无效的短信前缀", Toast.LENGTH_SHORT).show();
                    return;
                }
                setRunStateUI(true);
                mSmsForwarding.Start(((CheckBox) findViewById(R.id.fromLastSent)).isChecked());
            }
        }else if(view.getId() == mQRcodeBtn.getId()){
            Intent intent = new Intent();
            intent.setClass(this, SimpleScannerActivity.class);
            startActivityForResult(intent, 1);
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == 1) {
            String result = data.getExtras().getString("url");
            mEditServer.setText(result);
            serverUrlPreference(result);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mSmsReceiver);
        mSmsForwarding.Stop();
        super.onDestroy();
    }
}
