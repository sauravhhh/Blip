package com.sauravhhh.blip;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/**
 * Blip — offline Bluetooth text chat between two phones.
 * One phone taps Host (becomes discoverable), the other taps Scan
 * and taps the device. No internet needed.
 */
public class MainActivity extends Activity {

    private static final UUID APP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String SERVICE_NAME = "BlipChat";
    private static final int REQ_ENABLE_BT = 1;
    private static final int REQ_PERMS = 2;
    private static final int REQ_DISCOVERABLE = 300;

    private BluetoothAdapter bt;
    private TextView status;
    private EditText input;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayAdapter<String> msgAdapter;
    private final ArrayList<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private final ArrayList<String> deviceNames = new ArrayList<String>();
    private final ArrayList<String> messages = new ArrayList<String>();

    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private BluetoothDevice connectedDevice;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && !devices.contains(d)) {
                    devices.add(d);
                    deviceNames.add(label(d));
                    deviceAdapter.notifyDataSetChanged();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                setStatus("Scan finished. Tap a device to connect.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = (TextView) findViewById(R.id.status);
        input = (EditText) findViewById(R.id.input);
        ListView devList = (ListView) findViewById(R.id.devices);
        ListView msgList = (ListView) findViewById(R.id.messages);
        deviceAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, deviceNames);
        msgAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, messages);
        devList.setAdapter(deviceAdapter);
        msgList.setAdapter(msgAdapter);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bt = (bm == null) ? null : bm.getAdapter();
        if (bt == null) {
            setStatus("This device has no Bluetooth.");
            return;
        }

        devList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                connectTo(devices.get(pos));
            }
        });
        ((Button) findViewById(R.id.btnHost)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startHosting(); }
        });
        ((Button) findViewById(R.id.btnScan)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startScan(); }
        });
        ((Button) findViewById(R.id.btnSend)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        if (!hasPerms()) {
            requestPerms();
        } else {
            ensureBtOn();
        }
    }

    // ---------- permissions & bluetooth state ----------

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE };
        }
        return new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
    }

    private boolean hasPerms() {
        for (String p : neededPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestPerms() {
        requestPermissions(neededPerms(), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ_PERMS) {
            if (hasPerms()) ensureBtOn();
            else setStatus("Bluetooth permissions are needed to chat.");
        }
    }

    private void ensureBtOn() {
        if (bt.isEnabled()) {
            onBtReady();
        } else {
            startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == REQ_ENABLE_BT) {
            if (res == RESULT_OK) onBtReady();
            else setStatus("Bluetooth is off.");
        }
    }

    private void onBtReady() {
        setStatus("Bluetooth on. One phone taps Host, the other taps Scan.");
        loadBonded();
        listen();
    }

    // ---------- hosting / scanning ----------

    private void startHosting() {
        if (!hasPerms()) { requestPerms(); return; }
        listen();
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(i, REQ_DISCOVERABLE);
        setStatus("Hosting — this phone is visible for 5 minutes.");
    }

    private void listen() {
        if (acceptThread == null && connectedThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }

    private void startScan() {
        if (!hasPerms()) { requestPerms(); return; }
        loadBonded();
        try {
            if (bt.isDiscovering()) bt.cancelDiscovery();
        } catch (SecurityException e) { /* perms checked */ }
        boolean ok = false;
        try { ok = bt.startDiscovery(); } catch (SecurityException e) { /* perms checked */ }
        setStatus(ok ? "Scanning for nearby phones…" : "Could not start scan.");
    }

    private void loadBonded() {
        devices.clear();
        deviceNames.clear();
        try {
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    devices.add(d);
                    deviceNames.add(label(d) + " (paired)");
                }
            }
        } catch (SecurityException e) { /* perms checked */ }
        deviceAdapter.notifyDataSetChanged();
    }

    private String label(BluetoothDevice d) {
        String n;
        try { n = d.getName(); }
        catch (SecurityException e) { n = null; }
        if (n == null || n.isEmpty()) n = "Unknown device";
        return n + "\n" + d.getAddress();
    }

    // ---------- connection ----------

    private void connectTo(BluetoothDevice d) {
        if (connectedThread != null) disconnect();
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        try { bt.cancelDiscovery(); } catch (SecurityException e) { /* perms checked */ }
        setStatus("Connecting to " + label(d).replace("\n", " ") + "…");
        connectThread = new ConnectThread(d);
        connectThread.start();
    }

    private synchronized void onConnected(BluetoothSocket s, BluetoothDevice d) {
        connectThread = null;
        if (acceptThread != null) { acceptThread.cancel(); acceptThread = null; }
        if (connectedThread != null) connectedThread.cancel();
        connectedThread = new ConnectedThread(s);
        connectedThread.start();
        connectedDevice = d;
        final String name = label(d).replace("\n", " ");
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setStatus("Connected to " + name);
                addMsg("— Connected —");
            }
        });
    }

    private void onConnectFailed() {
        connectThread = null;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setStatus("Could not connect. Make sure the other phone tapped Host, then try again.");
            }
        });
    }

    private void onDisconnected() {
        connectedThread = null;
        connectedDevice = null;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setStatus("Disconnected.");
                addMsg("— Disconnected —");
            }
        });
        listen();
    }

    private void disconnect() {
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }
        connectedDevice = null;
        setStatus("Disconnected.");
        listen();
    }

    // ---------- chat ----------

    private void sendMessage() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        if (connectedThread == null) {
            toast("Not connected to anyone yet.");
            return;
        }
        connectedThread.write(t);
        input.setText("");
        onMessage(t, true);
    }

    private void onMessage(final String text, final boolean mine) {
        runOnUiThread(new Runnable() {
            @Override public void run() { addMsg((mine ? "Me: " : "Them: ") + text); }
        });
    }

    private void addMsg(String m) {
        messages.add(m);
        msgAdapter.notifyDataSetChanged();
    }

    private void setStatus(String s) { status.setText(s); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- threads ----------

    private class AcceptThread extends Thread {
        private BluetoothServerSocket server;
        AcceptThread() {
            try {
                server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID);
            } catch (IOException e) { server = null; }
        }
        @Override public void run() {
            if (server == null) return;
            while (true) {
                BluetoothSocket s;
                try { s = server.accept(); }
                catch (IOException e) { break; }
                if (s != null) {
                    onConnected(s, s.getRemoteDevice());
                    try { server.close(); } catch (IOException e) { /* done */ }
                    break;
                }
            }
        }
        void cancel() {
            try { if (server != null) server.close(); } catch (IOException e) { /* done */ }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothDevice dev;
        private BluetoothSocket sock;
        ConnectThread(BluetoothDevice d) {
            dev = d;
            try { sock = d.createRfcommSocketToServiceRecord(APP_UUID); }
            catch (IOException e) { sock = null; }
        }
        @Override public void run() {
            if (sock == null) { onConnectFailed(); return; }
            try { sock.connect(); }
            catch (IOException e) {
                onConnectFailed();
                try { sock.close(); } catch (IOException e2) { /* done */ }
                return;
            }
            onConnected(sock, dev);
        }
        void cancel() {
            try { if (sock != null) sock.close(); } catch (IOException e) { /* done */ }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket sock;
        private InputStream in;
        private OutputStream out;
        ConnectedThread(BluetoothSocket s) {
            sock = s;
            try {
                in = s.getInputStream();
                out = s.getOutputStream();
            } catch (IOException e) { in = null; out = null; }
        }
        @Override public void run() {
            if (in == null) { onDisconnected(); return; }
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            while (true) {
                int n;
                try { n = in.read(buf); }
                catch (IOException e) { onDisconnected(); break; }
                if (n <= 0) { onDisconnected(); break; }
                try { sb.append(new String(buf, 0, n, "UTF-8")); }
                catch (Exception e) { /* keep going */ }
                int idx;
                while ((idx = sb.indexOf("\n")) >= 0) {
                    String line = sb.substring(0, idx);
                    sb.delete(0, idx + 1);
                    if (!line.isEmpty()) onMessage(line, false);
                }
            }
        }
        void write(String msg) {
            try {
                if (out != null) {
                    out.write((msg + "\n").getBytes("UTF-8"));
                    out.flush();
                }
            } catch (IOException e) { /* connection likely dead */ }
        }
        void cancel() {
            try { sock.close(); } catch (IOException e) { /* done */ }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException e) { /* done */ }
        try { if (bt.isDiscovering()) bt.cancelDiscovery(); } catch (Exception e) { /* done */ }
        if (acceptThread != null) acceptThread.cancel();
        if (connectThread != null) connectThread.cancel();
        if (connectedThread != null) connectedThread.cancel();
    }
}
