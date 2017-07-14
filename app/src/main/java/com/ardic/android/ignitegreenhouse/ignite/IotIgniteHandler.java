package com.ardic.android.ignitegreenhouse.ignite;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.ardic.android.ignitegreenhouse.configuration.Configuration;
import com.ardic.android.ignitegreenhouse.managers.DataManager;
import com.ardic.android.iotignite.callbacks.ConnectionCallback;
import com.ardic.android.iotignite.enumerations.NodeType;
import com.ardic.android.iotignite.enumerations.ThingCategory;
import com.ardic.android.iotignite.enumerations.ThingDataType;
import com.ardic.android.iotignite.exceptions.AuthenticationException;
import com.ardic.android.iotignite.exceptions.UnsupportedVersionException;
import com.ardic.android.iotignite.listeners.NodeListener;
import com.ardic.android.iotignite.listeners.ThingListener;
import com.ardic.android.iotignite.nodes.IotIgniteManager;
import com.ardic.android.iotignite.nodes.Node;
import com.ardic.android.iotignite.things.Thing;
import com.ardic.android.iotignite.things.ThingActionData;
import com.ardic.android.iotignite.things.ThingData;
import com.ardic.android.iotignite.things.ThingType;

import java.util.List;

public class IotIgniteHandler implements ConnectionCallback, NodeListener, ThingListener {

    private static final String TAG = IotIgniteHandler.class.getSimpleName();

    // Static singleton instance
    private static IotIgniteHandler INSTANCE = null;
    private static final long IGNITE_RECONNECT_INTERVAL = 10000L;


    private static final String CONFIG_NODE_ID = "Configurator";
    private static final String CONFIG_THING_ID = "Configurator Thing";

    private IotIgniteManager mIotIgniteManager;
    private boolean igniteConnected = false;
    private Context appContext;
    private Handler igniteWatchdog = new Handler();

    private Node mConfiguratorNode;
    private Thing mConfiguratorThing;

    private Node mRegisterNode;
    private Thing mRegisterThing;

    private Intent getConfIntent = new Intent(INTENT_FILTER_CONFIG);
    private Intent intents = new Intent(INTENT_FILTER_IGNITE_STATUS);

    private Thing findThing;

    public static final String INTENT_FILTER_IGNITE_STATUS = "igniteConnect";
    public static final String INTENT_FILTER_CONFIG = "getConfig";

    public static final String INTENT_NODE_NAME = "getConfigPutNodeName";
    public static final String INTENT_THING_NAME = "getConfigPutThingName";
    public static final String INTENT_THING_FREQUENCY = "getConfigPutFrequency";

    private Configuration mConfiguration;
    private DataManager mDataManager;

    private ThingType mConfiguratorThingType = new ThingType(
            /** Define Type of your Thing */
            "Configuration Thing",

            /** Set your things vendor. It's useful if you are using real sensors
             * This is important for separating identical sensors manufactured by different vendors.
             * For example accelerometer sensor produced by Bosch data sampling is
             * different than Samsung's.*/
            "Seratonin Thing",

            /** Set thing data type.
             */
            ThingDataType.STRING
    );


    private Runnable igniteWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!igniteConnected) {
                rebuildIgnite();
                igniteWatchdog.postDelayed(this, IGNITE_RECONNECT_INTERVAL);
                Log.e(TAG, "Ignite is not connected. Trying to reconnect...");
            } else {
                Log.e(TAG, "Ignite is already connected.");
            }
        }
    };

    private IotIgniteHandler(Context context) {
        this.appContext = context;

    }

    public static synchronized IotIgniteHandler getInstance(Context appContext) {
        if (INSTANCE == null) {
            INSTANCE = new IotIgniteHandler(appContext);
        }
        return INSTANCE;
    }

    public void start() {
        startIgniteWatchdog();
    }

    @Override
    public void onConnected() {
        Log.i(TAG, "Ignite Connected");
        mConfiguration = Configuration.getInstance(appContext);
        mDataManager = DataManager.getInstance(appContext);
        igniteWatchdog.removeCallbacks(igniteWatchdogRunnable);
        igniteConnected = true;

        intents.putExtra("igniteStatus", true);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(intents);
        Log.i(TAG, "Ignite Send Broadcast (onConnected)");

        updateListener();

        if (registerConfiguratorNode() && registerConfiguratorThing()) {
            Log.i(TAG, "Configurator Node and Configurator Thing Created");
        }


        //TODO : PREFERENCES DE olan ile agent ta olan thing ve nodeları başka thread te karşılaştır
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "Ignite Disconnected");
        // start watchdog again here.
        igniteConnected = false;
        startIgniteWatchdog();
        intents.putExtra("igniteStatus", false);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(intents);
    }

    /**
     * Node Remove
     */
    @Override
    public void onNodeUnregistered(String s) {
        // mConfiguration.removeSavedNode(s);
    }

    @Override
    public void onConfigurationReceived(Thing thing) {

        /**
         * Thing configuration messages will be handled here.
         * For example data sending frequency or custom configuration may be in the incoming thing object.
         */
        getConfIntent.putExtra(INTENT_NODE_NAME, thing.getNodeID());
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(getConfIntent);

        getConfIntent.putExtra(INTENT_THING_NAME, thing.getThingID());
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(getConfIntent);

        getConfIntent.putExtra(INTENT_THING_FREQUENCY, thing.getThingConfiguration().getDataReadingFrequency());
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(getConfIntent);


    }

    @Override
    public void onActionReceived(String s, String s1, ThingActionData thingActionData) {
        /**
         * Thing action message will be handled here. Call thingActionData.getMessage()
         */
        mConfiguration.receivedConfigMessage(s, s1, thingActionData.getMessage());
        Log.i(TAG, "Action Node : " + s);
        Log.i(TAG, "Action Thing : " + s1);
        Log.i(TAG, "Action Message : " + thingActionData.getMessage());
    }

    @Override
    public void onThingUnregistered(final String s, final String s1) {

        /**
         * If your thing object is unregistered from outside world, you will receive this
         * information callback.
         */
        if (!s.equals(CONFIG_NODE_ID) && !s1.equals(CONFIG_THING_ID)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    mConfiguration.removeSavedThing(s, s1);
                }
            }).run();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    mDataManager.killThread(s, s1);
                }
            }).run();
        } else {
            if (registerConfiguratorNode() && registerConfiguratorThing()) {
                Log.i(TAG, "Configurator Node and Configurator Thing Created");
            }
        }

        Log.e(TAG, "Unregister : " + s + ":" + s1);
    }


    /**
     * Connect to iot ignite
     */
    private void rebuildIgnite() {
        try {
            mIotIgniteManager = new IotIgniteManager.Builder()
                    .setConnectionListener(this)
                    .setContext(appContext)
                    .build();
        } catch (UnsupportedVersionException e) {
            Log.e(TAG, "UnsupportedVersionException :" + e);
        }
    }


    /**
     * remove previous callback and setup new watchdog
     */
    private void startIgniteWatchdog() {
        igniteWatchdog.removeCallbacks(igniteWatchdogRunnable);
        igniteWatchdog.postDelayed(igniteWatchdogRunnable, IGNITE_RECONNECT_INTERVAL);

    }

    /**
     * Set all things and nodes connection to offline.
     * When the application close or destroyed.
     */
    public void shutdown() {
        try {
            for (Node mNode : IotIgniteManager.getNodeList()) {
                for (Thing mThing : getEveryThing(mNode)) {
                    mThing.setConnected(false, " Application Destroyed");
                    mNode.setConnected(false, "Application Destroyed");
                }
            }
        } catch (AuthenticationException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configurator Node
     */
    private boolean registerConfiguratorNode() {
        mConfiguratorNode = IotIgniteManager.NodeFactory.createNode(
                CONFIG_NODE_ID,
                CONFIG_NODE_ID,
                NodeType.GENERIC,
                null,
                this
        );

        if (mConfiguratorNode != null) {
            Log.i(TAG, mConfiguratorNode.getNodeID() + " created.");
            Log.i(TAG, mConfiguratorNode.getNodeID() + " is registering...");

            if (mConfiguratorNode.isRegistered() || mConfiguratorNode.register()) {

                /**
                 * Register node here. If registration is successful, make it online.
                 */

                Log.i(TAG, mConfiguratorNode.getNodeID() + " is registered successfully. Setting connection true");
                mConfiguratorNode.setConnected(true, "");
                return true;
            }

        }
        return false;
    }

    /**
     * Configurator Thing
     */
    private boolean registerConfiguratorThing() {
        if (mConfiguratorNode != null && mConfiguratorNode.isRegistered()) {
            mConfiguratorThing = mConfiguratorNode.createThing(
                    CONFIG_THING_ID,
                    mConfiguratorThingType,
                    ThingCategory.EXTERNAL,
                    true,
                    this,
                    null
            );
        }

        if (mConfiguratorThing != null) {
            Log.i(TAG, "Creating Thing ");

            if (mConfiguratorThing.isRegistered() || mConfiguratorThing.register()) {
                Log.i(TAG, "Thing[" + mConfiguratorThing.getThingID() + "]  is registered.");
                mConfiguratorThing.setConnected(true, "");
                return true;
            }
        }
        return false;
    }


    /**
     * Register Nodes
     */
    public boolean registerNode(String getNode) {
        mRegisterNode = IotIgniteManager.NodeFactory.createNode(
                getNode,
                getNode,
                NodeType.GENERIC,
                null,
                this
        );
        if (mRegisterNode != null) {
            Log.i(TAG, mRegisterNode.getNodeID() + " created.");
            Log.i(TAG, mRegisterNode.getNodeID() + " is registering...");

            if (mRegisterNode.isRegistered() || mRegisterNode.register()) {

                /**
                 * Register node here. If registration is successful, make it online.
                 */

                Log.i(TAG, mRegisterNode.getNodeID() + " is registered successfully. Setting connection true");
                mRegisterNode.setConnected(true, "");
                return true;
            }
        }
        return false;
    }

    /**
     * Register Thing
     */
    public boolean registerThing(String getThingLabel, String thingType, String thingVendor, String dataType) {
        ThingDataType getDataType;
        getDataType = ThingDataType.FLOAT; // TODO Gelen Değere Göre Böl
        if (mRegisterNode != null && mRegisterNode.isRegistered()) {
            mRegisterThing = mRegisterNode.createThing(
                    getThingLabel,
                    new ThingType(
                            thingType,
                            thingVendor,
                            getDataType
                    ),
                    ThingCategory.EXTERNAL,
                    true,
                    this,
                    null
            );
        }

        if (mRegisterThing != null) {
            Log.i(TAG, "Creating Thing ");
            if (mRegisterThing.isRegistered() || mRegisterThing.register()) {
                Log.i(TAG, "Thing[" + mRegisterThing.getThingID() + "]  is registered.");
                mRegisterThing.setConnected(true, "");
                return true;
            }
        }
        return false;
    }

    /**
     * Send Configurator
     */
    public boolean sendConfiguratorThingMessage(String sendMessage) {
        if (igniteConnected && mConfiguratorThing != null && mConfiguratorThing.isRegistered()) {
            ThingData data = new ThingData();
            data.addData(sendMessage);
            return mConfiguratorThing.sendData(data);
        }
        return false;
    }

    /**
     * Send Data
     */

    public void sendData(final String nodeName, final String thingName, final String value) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(nodeName) && !TextUtils.isEmpty(thingName) && (getThingList(nodeName, thingName) != null)) {
                    findThing = getThingList(nodeName, thingName);
                } else if (!mConfiguration.getSavedDevices(nodeName, thingName).equals(mConfiguration.PREFERENCES_ADD_SENSOR_NOT_GET) && getThingList(nodeName, thingName) == null) {
                    Log.e(TAG, "Not Find Cloud : Node : " + nodeName + " : " + thingName);
                    registerNode(nodeName);
                    registerThing(thingName, "Seratonin", "Seraton", "d");
                } else if (!(getThingList(nodeName, thingName) == null) && !getThingList(nodeName, thingName).isRegistered()) {
                    Log.e(TAG, "Not Register Node : " + nodeName + " - Thing : " + thingName);
                    registerNode(nodeName);
                    registerThing(thingName, "Seratonin", "Seraton", "d");
                }

                if (igniteConnected && findThing != null && findThing.isRegistered()) {
                    ThingData data = new ThingData();
                    data.addData(Double.parseDouble(value));
                    Log.e(TAG, "Send Node : " + nodeName +
                            "\nThing : " + thingName +
                            "\nValue : " + value);
                    findThing.sendData(data);
                }
            }
        }).run();
    }

    public final List<Thing> getEveryThing(Node mNode) {
        return mNode.getEveryThing();
    }

    public void clearAllThing() {
        try {
            for (Node mNode : IotIgniteManager.getNodeList()) {
                for (Thing mThing : getEveryThing(mNode)) {
                    mThing.unregister();
                    Log.e(TAG, "Thing List : " + mNode.getEveryThing());

                }
                mNode.unregister();
            }
            if (registerConfiguratorNode() && registerConfiguratorThing()) {
                Log.i(TAG, "Configurator Node and Configurator Thing Created");
            }
        } catch (AuthenticationException e) {
            e.printStackTrace();
        }
    }

    public Thing getThingList(String nodeName, String thingName) {
        try {
            for (Node mNode : IotIgniteManager.getNodeList()) {
                for (Thing mThing : getEveryThing(mNode)) {
                    if (mNode.getNodeID().equals(nodeName) && mThing.getThingID().equals(thingName)) {
                        return mThing;
                    }
                }
            }
        } catch (AuthenticationException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void updateListener() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (Node mNode : IotIgniteManager.getNodeList()) {
                        mNode.setNodeListener(IotIgniteHandler.this);
                        mNode.register();
                        for (Thing mThing : getEveryThing(mNode)) {
                            mThing.setThingListener(IotIgniteHandler.this);
                            if (registerNode(mThing.getNodeID()) && registerThing(mThing.getThingID(), "Seratonin", "Seraton", "d")) {
                                Log.i(TAG, mThing.getNodeID() + "  Node and " + mThing.getThingID() + " Thing Control");
                            }
                           /* if (!mThing.isRegistered()) {
                                if (registerNode(mThing.getNodeID()) && registerThing(mThing.getThingID(), "Seratonin", "Seraton", "d")) {
                                    Log.i(TAG, mThing.getNodeID() + "  Node and " + mThing.getThingID() + " Thing Control");
                                }
                            }*/

                            getConfIntent.putExtra(INTENT_NODE_NAME, mThing.getNodeID());
                            LocalBroadcastManager.getInstance(appContext).sendBroadcast(getConfIntent);

                            getConfIntent.putExtra(INTENT_THING_NAME, mThing.getThingID());
                            LocalBroadcastManager.getInstance(appContext).sendBroadcast(getConfIntent);

                            getConfIntent.putExtra(INTENT_THING_FREQUENCY, mThing.getThingConfiguration().getDataReadingFrequency());
                            LocalBroadcastManager.getInstance(appContext).sendBroadcast(getConfIntent);
                        }
                    }
                } catch (AuthenticationException e) {
                    e.printStackTrace();
                }
            }
        }).run();

    }

    public long getConfigurationTime(String nodeThing) {
        try {
            for (Node mNode : IotIgniteManager.getNodeList()) {
                for (Thing mThing : getEveryThing(mNode)) {
                    if (nodeThing.equals(mThing.getNodeID() + ":" + mThing.getThingID())) {
                        Log.i(TAG, "Configuration desired : " + nodeThing);
                        return mThing.getThingConfiguration().getDataReadingFrequency();
                    }
                }
            }
        } catch (AuthenticationException e) {
            e.printStackTrace();
        }
        return -5;
    }
}