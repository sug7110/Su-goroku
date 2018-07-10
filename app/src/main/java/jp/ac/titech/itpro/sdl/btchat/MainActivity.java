package jp.ac.titech.itpro.sdl.btchat;

import android.app.Activity;
import android.content.DialogInterface;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Random;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;

import jp.ac.titech.itpro.sdl.btchat.message.ChatMessage;
import jp.ac.titech.itpro.sdl.btchat.message.ChatMessageReader;
import jp.ac.titech.itpro.sdl.btchat.message.ChatMessageWriter;

public class MainActivity extends AppCompatActivity {

    private final static String TAG = "MainActivity";

    private TextView statusText;
    private ProgressBar connectionProgress;
    private ListView chatLogView;
    private EditText inputText;
    private Button sendButton;

    private ArrayList<ChatMessage> chatLog;
    private ArrayAdapter<ChatMessage> chatLogAdapter;

    private BluetoothAdapter btAdapter;
    private final static int REQCODE_ENABLE_BT = 1111;
    private final static int REQCODE_GET_DEVICE = 2222;
    private final static int REQCODE_DISCOVERABLE = 3333;

    private enum State {
        Initializing,
        Disconnected,
        Connecting,
        Connected,
        Waiting,
        MyTURN,
        notMyTURN,
        Finish
    }

    private State state = State.Initializing;

    private final static String SPP_UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB";
    private final static UUID SPP_UUID = UUID.fromString(SPP_UUID_STRING);

    private final static int SERVER_TIMEOUT_SEC = 90;

    private String devName = "?";
    private int message_seq = 0;

    private ClientTask clientTask;
    private ServerTask serverTask;

    private CommThread commThread;

    private SoundPool soundPool;
    private int sound_connected;
    private int sound_disconnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        chatLog = new ArrayList<>();

        statusText = findViewById(R.id.conn_status_text);
        connectionProgress = findViewById(R.id.connection_progress);
        sendButton = findViewById(R.id.send_button);
        chatLogView = findViewById(R.id.chat_log_view);
        chatLogAdapter = new ArrayAdapter<ChatMessage>(this, 0, chatLog) {
            @Override
            public @NonNull
            View getView(int pos, @Nullable View view, @NonNull ViewGroup parent) {
                if (view == null) {
                    LayoutInflater inflater = LayoutInflater.from(getContext());
                    view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                }
                ChatMessage message = getItem(pos);
                assert message != null;
                TextView text1 = view.findViewById(android.R.id.text1);
                text1.setTextColor(message.sender.equals(devName) ? Color.GRAY : Color.BLACK);
                text1.setText(message.content);
                return view;
            }
        };
        chatLogView.setAdapter(chatLogAdapter);
        final DateFormat dateFormat = DateFormat.getDateTimeInstance();
        chatLogView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                ChatMessage message = (ChatMessage) parent.getItemAtPosition(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.message_title, message.seq, message.sender))
                        .setMessage(getString(R.string.message_content,
                                message.content, dateFormat.format(new Date(message.time))))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();

            }
        });

        setState(State.Initializing);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setAudioAttributes(attrs)
                .setMaxStreams(1)
                .build();
        // Sound Effects by NHK Creative Library
        // https://www.nhk.or.jp/archives/creative/material/view.html?m=D0002011524_00000
        sound_connected = soundPool.load(this, R.raw.nhk_doorbell, 1);
        // https://www.nhk.or.jp/archives/creative/material/view.html?m=D0002070102_00000
        sound_disconnected = soundPool.load(this, R.raw.nhk_woodblock2, 1);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null)
            setupBT();
        else {
            Toast.makeText(this, R.string.toast_bluetooth_not_supported,
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (state == State.Connected)
            commThread.close();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "onCreateOptionsMenu");
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        Log.d(TAG, "onPrepareOptionsMenu");
        MenuItem itemConnect = menu.findItem(R.id.menu_connect);
        MenuItem itemDisconnect = menu.findItem(R.id.menu_disconnect);
        MenuItem itemServerStart = menu.findItem(R.id.menu_server_start);
        MenuItem itemServerStop = menu.findItem(R.id.menu_server_stop);
        switch (state) {
        case Initializing:
        case Connecting:
            itemConnect.setVisible(false);
            itemDisconnect.setVisible(false);
            itemServerStart.setVisible(false);
            itemServerStop.setVisible(false);
            return true;
        case Disconnected:
            itemConnect.setVisible(true);
            itemDisconnect.setVisible(false);
            itemServerStart.setVisible(true);
            itemServerStop.setVisible(false);
            return true;
        case Connected:
            itemConnect.setVisible(false);
            itemDisconnect.setVisible(true);
            itemServerStart.setVisible(false);
            itemServerStop.setVisible(false);
            return true;
        case Waiting:
            itemConnect.setVisible(false);
            itemDisconnect.setVisible(false);
            itemServerStart.setVisible(false);
            itemServerStop.setVisible(true);
            return true;
        default:
            return super.onPrepareOptionsMenu(menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected");
        switch (item.getItemId()) {
        case R.id.menu_connect:
            connect();
            return true;
        case R.id.menu_disconnect:
            disconnect();
            return true;
        case R.id.menu_server_start:
            startServer();
            return true;
        case R.id.menu_server_stop:
            stopServer();
            return true;
        case R.id.menu_clear:
            chatLogAdapter.clear();
            return true;
        case R.id.menu_about:
            new AlertDialog.Builder(this)
                    .setTitle(R.string.about_dialog_title)
                    .setMessage(R.string.about_dialog_content)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int reqCode, int resCode, Intent data) {
        Log.d(TAG, "onActivityResult : reqCode=" + reqCode + " resCode=" + resCode);
        switch (reqCode) {
        case REQCODE_ENABLE_BT:
            if (resCode == Activity.RESULT_OK)
                setupBT1();
            else {
                Toast.makeText(this, R.string.toast_bluetooth_disabled,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
            break;
        case REQCODE_GET_DEVICE:
            if (resCode == Activity.RESULT_OK)
                connect1((BluetoothDevice) data.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
            else
                setState(State.Disconnected);
            break;
        case REQCODE_DISCOVERABLE:
            if (resCode != Activity.RESULT_CANCELED)
                startServer1();
            else
                setState(State.Disconnected);
        }
    }
// SOLVEボタンを押した時の挙動
    public void onClickSolveButton(View v) {
        final long clicktime = System.currentTimeMillis();
        Log.d(TAG, "onClickSolveButton");
        final EditText editView = new EditText(MainActivity.this);
        final Question q = new Question();
        final Question qmat = q.MakeQuestion();
        new AlertDialog.Builder(MainActivity.this)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setTitle("問題: " + qmat.qs + " ?")
                .setView(editView)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        long solvetime = System.currentTimeMillis();
                        long duration = solvetime-clicktime;
                        int moves = 0;
                        boolean correct;
                        boolean goal = false;
                        int answer = 0;
                        if(editView.getText().toString().length() != 0 ){
                            try{
                                answer = Integer.parseInt(editView.getText().toString());
                            }
                            catch(NumberFormatException e){
                                answer = 0;
                            }
                        }
                        if (answer == qmat.answer) {
                            correct = true;
                            moves = calculatemoves(answer, duration);
                            Sugoroku.totalmoves += moves;
                            if (Sugoroku.totalmoves >= Sugoroku.n_goal){
                                goal = true;
                                Toast.makeText(MainActivity.this,
                                        "ゴール!おめでとう!",
                                        Toast.LENGTH_LONG).show();
                            }
                            else{
                                Toast.makeText(MainActivity.this,
                                        "正解!",
                                        Toast.LENGTH_LONG).show();

                            }
                        } else {
                            correct = false;
                            Toast.makeText(MainActivity.this,
                                    "不正解!",
                                    Toast.LENGTH_LONG).show();
                    }
                        String content;
                        if (!correct){
                            content = String.format("%s - 不正解!" + "\n" +
                                            "%s %d", devName.substring(0,4),qmat.qs, qmat.answer);
                        } else {
                            content = String.format("%s - 正解!" + "\n" +
                                            "%s %d(in %2ds), %dマス進みます",
                                    devName.substring(0,4), qmat.qs, qmat.answer, duration/1000, moves);
                        }
                        ChatMessage message = new ChatMessage(message_seq, solvetime, content, devName);
                        commThread.send(message);
                        chatLogAdapter.add(message);
                        chatLogAdapter.notifyDataSetChanged();
                        chatLogView.smoothScrollToPosition(chatLog.size());
                        setState(State.notMyTURN);
                        if(goal){
                            content = String.format("%sがゴールしました", devName.substring(0,4));
                            message = new ChatMessage(message_seq, solvetime, content, devName);
                            commThread.send(message);
                            chatLogAdapter.add(message);
                            chatLogAdapter.notifyDataSetChanged();
                            chatLogView.smoothScrollToPosition(chatLog.size());
                            setState(State.Finish);
                        }
                    }
                })
                .setNegativeButton("キャンセル", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        long solvetime = System.currentTimeMillis();
                        Toast.makeText(MainActivity.this,
                                "Never give up",
                                Toast.LENGTH_LONG).show();
                        String content = String.format("%s - 不正解!" + "\n" +
                                                "%s %d", devName.substring(0,4),qmat.qs, qmat.answer);
                        ChatMessage message = new ChatMessage(message_seq, solvetime, content, devName);
                        commThread.send(message);
                        chatLogAdapter.add(message);
                        chatLogAdapter.notifyDataSetChanged();
                        chatLogView.smoothScrollToPosition(chatLog.size());
                        setState(State.notMyTURN);
                    }
                })
                .show();

    }

    private void setupBT() {
        Log.d(TAG, "setupBT");
        if (!btAdapter.isEnabled())
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQCODE_ENABLE_BT);
        else
            setupBT1();
    }

    private void setupBT1() {
        Log.d(TAG, "setupBT1");
        devName = btAdapter.getName();
        setState(State.Disconnected);
    }


    // Client-Side

    private void connect() {
        Log.d(TAG, "connect");
        startActivityForResult(new Intent(this, BTScanActivity.class), REQCODE_GET_DEVICE);
    }

    private void connect1(BluetoothDevice device) {
        Log.d(TAG, "connect1");
        clientTask = new ClientTask(this);
        clientTask.execute(device);
        setState(State.Connecting, device.getName());
    }

    private void connect2() {
        Log.d(TAG, "connect2");
        connectionProgress.setIndeterminate(true);
    }

    private void connect3(BluetoothSocket socket) {
        Log.d(TAG, "connect3");
        connectionProgress.setIndeterminate(false);
        if (socket != null) {
            try {
                commThread = new CommThread(socket, commHandler);
                commThread.start();
            }
            catch (IOException e) {
                e.printStackTrace();
                try {
                    socket.close();
                }
                catch (IOException e1) {
                    e1.printStackTrace();
                }
                setState(State.Disconnected);
            }
        }
        else {
            Toast.makeText(MainActivity.this,
                    R.string.toast_connection_failed, Toast.LENGTH_SHORT).show();
            setState(State.Disconnected);
        }
        clientTask = null;
    }

    private static class ClientTask extends AsyncTask<BluetoothDevice, Void, BluetoothSocket> {
        private final static String TAG = "MainActivity.ClientTask";

        private WeakReference<MainActivity> activityRef;

        ClientTask(MainActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "onPreExecute");
            MainActivity activity = activityRef.get();
            if (activity != null)
                activity.connect2();
        }

        @Override
        protected BluetoothSocket doInBackground(BluetoothDevice... params) {
            Log.d(TAG, "doInBackground");
            BluetoothSocket socket = null;
            try {
                socket = params[0].createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            }
            catch (IOException e) {
                e.printStackTrace();
                if (socket != null) {
                    try {
                        socket.close();
                    }
                    catch (IOException e1) {
                        e1.printStackTrace();
                    }
                    socket = null;
                }
            }
            return socket;
        }

        @Override
        protected void onPostExecute(BluetoothSocket socket) {
            Log.d(TAG, "onPostExecute");
            MainActivity activity = activityRef.get();
            if (activity != null)
                activity.connect3(socket);
        }
    }

    // Server-Side

    private void startServer() {
        Log.d(TAG, "startServer");
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, SERVER_TIMEOUT_SEC);
        startActivityForResult(intent, REQCODE_DISCOVERABLE);
    }

    private void startServer1() {
        Log.d(TAG, "startServer1");
        serverTask = new ServerTask(this);
        serverTask.execute(SERVER_TIMEOUT_SEC);
        setState(State.Waiting);
    }

    private void startServer2() {
        Log.d(TAG, "startServer2");
        connectionProgress.setIndeterminate(true);
    }

    private void startServer3(BluetoothSocket socket) {
        Log.d(TAG, "startServer3");
        connectionProgress.setIndeterminate(false);
        if (socket != null) {
            try {
                commThread = new CommThread(socket, commHandler);
                commThread.start();
            }
            catch (IOException e) {
                try {
                    socket.close();
                }
                catch (IOException e1) {
                    e1.printStackTrace();
                }
                setState(State.Disconnected);
            }
        }
        else {
            Toast.makeText(MainActivity.this, R.string.toast_connection_failed,
                    Toast.LENGTH_SHORT).show();
            setState(State.Disconnected);
        }
        serverTask = null;
    }

    private void cancelServer() {
        Log.d(TAG, "cancelServer");
        connectionProgress.setIndeterminate(false);
        setState(State.Disconnected);
        serverTask = null;
    }

    private void stopServer() {
        Log.d(TAG, "stopServer");
        if (serverTask != null)
            serverTask.stop();
    }


    private static class ServerTask extends AsyncTask<Integer, Void, BluetoothSocket> {
        private final static String TAG = "MainActivity.ServerTask";
        private BluetoothServerSocket serverSocket;
        private WeakReference<MainActivity> activityRef;
        private BluetoothAdapter btAdapter;
        private String devName;

        ServerTask(MainActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "onPreExecute");
            MainActivity activity = activityRef.get();
            if (activity != null) {
                btAdapter = activity.btAdapter;
                devName = activity.devName;
                activity.startServer2();
            }
        }

        @Override
        protected BluetoothSocket doInBackground(Integer... params) {
            Log.d(TAG, "doInBackground");
            BluetoothSocket socket;
            try {
                serverSocket = btAdapter.
                        listenUsingRfcommWithServiceRecord(devName, SPP_UUID);
                socket = serverSocket.accept(params[0] * 1000);
            }
            catch (IOException e) {
                e.printStackTrace();
                socket = null;
            }
            finally {
                try {
                    serverSocket.close();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return socket;
        }

        @Override
        protected void onPostExecute(BluetoothSocket socket) {
            Log.d(TAG, "onPostExecute");
            MainActivity activity = activityRef.get();
            if (activity != null)
                activity.startServer3(socket);
        }

        @Override
        protected void onCancelled() {
            Log.d(TAG, "onCancelled");
            MainActivity activity = activityRef.get();
            if (activity != null)
                activity.cancelServer();
        }

        void stop() {
            Log.d(TAG, "stop");
            try {
                serverSocket.close();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            cancel(false);
        }
    }


    // Thread and Hander for Bidirectional Communication

    private class CommThread extends Thread {
        private final static String TAG = "CommThread";
        private final BluetoothSocket socket;
        private final ChatMessageReader reader;
        private final ChatMessageWriter writer;
        private CommHandler handler;
        private boolean writerClosed = false;

        CommThread(BluetoothSocket socket, CommHandler handler) throws IOException {
            if (!socket.isConnected())
                throw new IOException("Socket is not connected");
            this.socket = socket;
            reader = new ChatMessageReader(new JsonReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8")));
            writer = new ChatMessageWriter(new JsonWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8")));
            this.handler = handler;
        }

        @Override
        public void run() {
            Log.d(TAG, "run");
            handler.sendMessage(handler.obtainMessage(MESG_STARTED, socket.getRemoteDevice()));
            try {
                writer.beginArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    handler.sendMessage(handler.obtainMessage(MESG_RECEIVED, reader.read()));
                }
                reader.endArray();
                if (!writerClosed) {
                    writer.endArray();
                    writer.flush();
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                if (socket.isConnected()) {
                    try {
                        socket.close();
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            handler.sendMessage(handler.obtainMessage(MESG_FINISHED));
        }

        void send(ChatMessage message) {
            Log.d(TAG, "send");
            try {
                writer.write(message);
                writer.flush();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }

        void close() {
            Log.d(TAG, "close");
            try {
                writer.endArray();
                writer.flush();
                writerClosed = true;
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final static int MESG_STARTED = 1111;
    private final static int MESG_RECEIVED = 2222;
    private final static int MESG_FINISHED = 3333;

    private static class CommHandler extends Handler {
        private final static String TAG = "CommHandler";
        WeakReference<MainActivity> activityRef;

        CommHandler(MainActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage");
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            switch (msg.what) {
            case MESG_STARTED:
                BluetoothDevice device = (BluetoothDevice) msg.obj;
                activity.setState(State.Connected, device.getName());
                break;
            case MESG_FINISHED:
                Toast.makeText(activity, R.string.toast_connection_closed,
                        Toast.LENGTH_SHORT).show();
                activity.setState(State.Disconnected);
                break;
            case MESG_RECEIVED:
                activity.showMessage((ChatMessage) msg.obj);
                String message = toString().valueOf(msg.obj);
                if(message.contains("ゴール")){
                    activity.setState(State.Finish);
                }
                else{
                    activity.setState(State.MyTURN);
                }
                break;
            }
        }
    }

    private CommHandler commHandler = new CommHandler(this);

    private void setState(State state) {
        setState(state, null);
    }

    private void setState(State state, String arg) {
        this.state = state;
        int rest = Sugoroku.n_goal-Sugoroku.totalmoves;
        String myturntext = String.format("あなたのターンです。ゴールまであと%dマスです！",rest);
        String notmyturntext = String.format("相手のターンです。ゴールまであと%dマスです！",rest);
        switch (state) {
        case Initializing:
            statusText.setText(R.string.conn_status_text_disconnected);
            sendButton.setEnabled(false);
            break;
        case Disconnected:
            Sugoroku.totalmoves = 0;
            statusText.setText(R.string.conn_status_text_disconnected);
            sendButton.setEnabled(false);
            break;
        case Connecting:
            statusText.setText(getString(R.string.conn_status_text_connecting_to, arg));
            sendButton.setEnabled(false);
            break;
        case Connected:
            statusText.setText(getString(R.string.conn_status_text_connected_to, arg));
            sendButton.setEnabled(true);
            soundPool.play(sound_connected, 1.0f, 1.0f, 0, 0, 1);
            break;
        case Waiting:
            statusText.setText(R.string.conn_status_text_waiting_for_connection);
            sendButton.setEnabled(false);
            break;
        // ゲーム中の表示列
        // xmlに文字列を登録するとうまいこといってくれないので上で文字列を直接定義
        case MyTURN:
            statusText.setText(myturntext);
            sendButton.setEnabled(true);
            break;
        case notMyTURN:
            statusText.setText(notmyturntext);
            sendButton.setEnabled(false);
            break;
        case Finish:
            Sugoroku.totalmoves = 0;
            statusText.setText(R.string.conn_status_text_finished);
            sendButton.setEnabled(true);

        }
        invalidateOptionsMenu();
    }

    private void showMessage(ChatMessage message) {
        chatLogAdapter.add(message);
        chatLogAdapter.notifyDataSetChanged();
        chatLogView.smoothScrollToPosition(chatLogAdapter.getCount());
    }

    private void disconnect() {
        Log.d(TAG, "disconnect");
        if (commThread != null) {
            commThread.close();
            commThread = null;
        }
        setState(State.Disconnected);
        soundPool.play(sound_disconnected, 1.0f, 1.0f, 0, 0, 1);
    }
    public int calculatemoves(int answer, long time){
        return((int)((Math.log(answer)/time)*2000)+1);
    }
}
class Question{
   public int x;
   public int y;
   public String qs;
   public int answer;
   public Question MakeQuestion(){
       Random rnd = new Random();
       Question q = new Question();
       q.x = rnd.nextInt(90) + 10;
       q.y = rnd.nextInt(90) + 10;
       q.qs = String.format("%d × %d = ", q.x, q.y);
       q.answer = q.x * q.y;
       return q;
   }
}
class Sugoroku{
    public static final int n_goal = 10;
    public static int totalmoves = 0;
}