package com.ardic.android.ignitegreenhouse.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.ardic.android.ignitegreenhouse.ignite.IotIgniteHandler;
import com.ardic.android.ignitegreenhouse.constants.Constant;

/**
 * Created by acel on 7/7/17.
 */

public class ThreadManager extends Thread {

    private static final String TAG = ThreadManager.class.getSimpleName();
    private Handler sendDataHandler;
    private String getNodeName;
    private String getThingName;
    private String getValue;
    private long getDelayTime = -5;
    private boolean getMessageFlag = false;
    private IotIgniteHandler mIotIgniteHandler;

    boolean getRunnableFlag = true;

    private Context mContext;


    private boolean broadCastFlag = false;

    private static final long CONFIG_READ_DELAY_TIME = 60000;

    private Handler configReadHandler ;

    private BroadcastReceiver getIgniteConfig = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            broadCastFlag = true;
        }
    };

    private Runnable sendDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (getMessageFlag) {
                if (!TextUtils.isEmpty(getNodeName) && !TextUtils.isEmpty(getThingName) && !TextUtils.isEmpty(getValue)) {
                    mIotIgniteHandler.sendData(getNodeName, getThingName, getValue);
                    getRunnableFlag = false;
                    getMessageFlag = false;
                    if (broadCastFlag) {
                        getDelayTime = (IotIgniteHandler.getInstance(mContext).getConfigurationTime(getNodeName + ":" + getThingName));
                        if (getDelayTime == -5 || getDelayTime == -1 ) {
                            configReadHandler.postDelayed(configRead, CONFIG_READ_DELAY_TIME);
                        }
                        broadCastFlag = false;
                    }
                    if (Constant.DEBUG) {
                        Log.i(TAG, "Node : " + getNodeName + " - Thing : " + getThingName + " - Value : " + getValue + " - Delay : " + getDelayTime);
                    }
                }
            }
            if (sendDataHandler != null) {
                sendDataHandler.postDelayed(this, getDelayTime);
            }
        }
    };

    public ThreadManager(Context context) {
        mContext = context;
        mIotIgniteHandler = IotIgniteHandler.getInstance(mContext);

        sendDataHandler = new Handler(Looper.getMainLooper());

        configReadHandler = new Handler(Looper.getMainLooper());
        LocalBroadcastManager.getInstance(context).registerReceiver(getIgniteConfig,
                new IntentFilter(Constant.INTENT_FILTER_CONFIG));
        broadCastFlag=true;

        this.run();
    }


    private Runnable configRead = new Runnable() {
        @Override
        public void run() {
            getDelayTime = (IotIgniteHandler.getInstance(mContext).getConfigurationTime(getNodeName + ":" + getThingName));
            if (getDelayTime == -5 || getDelayTime == -1) {
                configReadHandler.postDelayed(this, CONFIG_READ_DELAY_TIME);
            }
        }
    };


    public void parseData(String nodeName, String getThingName, String getValue) {
        if (Constant.DEBUG) {
            Log.i(TAG, "Parse Data Node Name : " + nodeName +
                    "\nThing Name : " + getThingName +
                    "\nMessage : " + getValue);
        }
        this.getNodeName = nodeName;
        this.getThingName = getThingName;
        this.getValue = getValue;
        this.getMessageFlag = true;
    }

    @Override
    public void run() {
        super.run();
        if (getRunnableFlag) {
            sendDataHandler.post(sendDataRunnable);
        }
    }


    public void stopThread() {
        getDelayTime = 0;
        sendDataHandler.removeCallbacks(sendDataRunnable);
        sendDataHandler = null;
        this.interrupt();
    }

}
