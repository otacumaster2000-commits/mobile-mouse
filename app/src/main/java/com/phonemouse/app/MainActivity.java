package com.phonemouse.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import android.text.InputType;
import java.io.*;
import java.net.*;
import java.util.concurrent.LinkedBlockingDeque;

public class MainActivity extends Activity {
    private final int BG=Color.rgb(20,24,34), PANEL=Color.rgb(37,43,58), LIGHT=Color.rgb(236,239,246), ACCENT=Color.rgb(89,206,225);
    private EditText pin;
    private TextView status, quality;
    private SeekBar sensitivity;
    private Switch inverseSwitch;
    private Button startButton;
    private boolean running=false, invert=false;
    private final LinkedBlockingDeque<String> outgoing=new LinkedBlockingDeque<>(180);
    private volatile boolean networkRunning=false;
    private Thread socketThread;
    private Socket socket;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener motionListener;
    private final float[] gravity=new float[3];
    private final float[] bias=new float[2];
    private final float[] filtered=new float[2];
    private boolean gravityReady=false;
    private long previousSensorNs=0, lastReportNs=0;
    private double velocityX=0, velocityY=0, residualX=0, residualY=0;
    private int stillSamples=0, sensorEvents=0;
    private volatile int sensitivityValue=85;
    private volatile boolean mouseConnected=false;
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(8),dp(12),dp(12));
        root.setBackgroundColor(BG);
        setContentView(root);

        TextView heading=text("PhoneMouse",25,true);
        heading.setTextColor(ACCENT);
        root.addView(heading);

        LinearLayout login=new LinearLayout(this);
        login.setOrientation(LinearLayout.HORIZONTAL);
        login.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(login,new LinearLayout.LayoutParams(-1,dp(62)));
        pin=field("Шестизначный PIN с ПК", "", 2);
        pin.setTextSize(19);
        pin.setSelectAllOnFocus(true);
        pin.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        login.addView(pin,new LinearLayout.LayoutParams(0,dp(54),1.0f));
        startButton=button("ПОДКЛЮЧИТЬ");
        startButton.setTextSize(13);
        login.addView(startButton,new LinearLayout.LayoutParams(dp(146),dp(54)));
        startButton.setOnClickListener(v->{
            if(running)stopEverything();
            else startEverything();
        });

        status=text("Соедини USB и запусти Windows-программу, введи PIN",13,false);
        root.addView(status);
        quality=text("Акселерометр выключен",12,false);
        root.addView(quality);

        // The mouse zones take all available room, without scrolling or clipped labels.
        LinearLayout mouseZones=new LinearLayout(this);
        mouseZones.setOrientation(LinearLayout.HORIZONTAL);
        mouseZones.setPadding(0,dp(8),0,dp(8));
        root.addView(mouseZones,new LinearLayout.LayoutParams(-1,0,1.0f));
        Button left=button("ЛКМ");
        Button right=button("ПКМ");
        left.setTextSize(25);right.setTextSize(25);
        mouseZones.addView(left,new LinearLayout.LayoutParams(0,-1,1.0f));
        TextView wheel=text("▲\n\nСКРОЛЛ\n\n▼",14,true);
        wheel.setGravity(Gravity.CENTER);
        wheel.setBackgroundColor(Color.rgb(51,62,78));
        LinearLayout.LayoutParams wheelParams=new LinearLayout.LayoutParams(dp(94),-1);
        wheelParams.setMargins(dp(5),0,dp(5),0);
        mouseZones.addView(wheel,wheelParams);
        mouseZones.addView(right,new LinearLayout.LayoutParams(0,-1,1.0f));
        setupMouseButton(left,1);setupMouseButton(right,2);
        wheel.setOnTouchListener(new View.OnTouchListener(){float y,acc;
            @Override public boolean onTouch(View v,MotionEvent ev){
                if(ev.getActionMasked()==MotionEvent.ACTION_DOWN){y=ev.getY();acc=0;return true;}
                if(ev.getActionMasked()==MotionEvent.ACTION_MOVE){
                    acc+=y-ev.getY();y=ev.getY();
                    int ticks=(int)(acc/dp(24));
                    if(ticks!=0){send("W "+ticks);acc-=ticks*dp(24);}
                    return true;
                }
                return true;
            }
        });

        LinearLayout toggles=new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(toggles);
        inverseSwitch=new Switch(this);
        inverseSwitch.setText("Инверсия движения"); inverseSwitch.setTextColor(LIGHT);
        toggles.addView(inverseSwitch,new LinearLayout.LayoutParams(0,dp(48),1));
        inverseSwitch.setOnCheckedChangeListener((v,checked)->invert=checked);
        Button calibrate=button("СБРОС ДРЕЙФА");
        toggles.addView(calibrate,new LinearLayout.LayoutParams(0,dp(48),1));
        calibrate.setOnClickListener(v->{resetMotion();setStatus("Датчик сброшен. Положи телефон ровно.");});
        root.addView(text("Чувствительность",13,false));
        sensitivity=new SeekBar(this);
        sensitivity.setMax(300);sensitivity.setProgress(85);
        root.addView(sensitivity,new LinearLayout.LayoutParams(-1,dp(36)));
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int v,boolean fromUser){sensitivityValue=v;}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        sensorManager=(SensorManager)getSystemService(Context.SENSOR_SERVICE);
        accelerometer=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if(accelerometer==null)quality.setText("Акселерометр не найден");
    }
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(LIGHT);t.setTextSize(sp);t.setPadding(5,8,5,8);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private EditText field(String hint,String def,int type){EditText e=new EditText(this); e.setSingleLine(true);e.setText(def);e.setHint(hint);e.setTextColor(LIGHT);e.setHintTextColor(0xff8993a8);e.setTextSize(15);e.setInputType(type==2?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT);e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));return e;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(LIGHT);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));b.setTextSize(12);b.setAllCaps(false);return b;}
    private void setupMouseButton(Button b,int which){b.setOnTouchListener((v,e)->{
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){send("B "+which+" 1");b.setAlpha(.55f);return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){send("B "+which+" 0");b.setAlpha(1f);return true;}
        return true;});}
    private void send(String message){if(!networkRunning)return; if(!outgoing.offerLast(message)){outgoing.pollFirst();outgoing.offerLast(message);}}
    private void setStatus(String msg){runOnUiThread(()->status.setText(msg));}
    private void startEverything(){
        if(accelerometer==null){setStatus("Нет акселерометра");return;}
        String code=pin.getText().toString().trim();
        if(!code.matches("[0-9]{6}")){setStatus("Введи 6 цифр PIN из программы Windows");return;}
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(pin.getWindowToken(),0);
        pin.clearFocus();
        running=true; startButton.setText("ОТКЛЮЧИТЬ"); networkRunning=true;
        startNetworking(code); startMotion();
    }
    private void startNetworking(String code){
        socketThread=new Thread(()->{
            while(networkRunning){
                try{
                    setStatus("USB: подключаюсь через ADB reverse...");
                    try(Socket s=new Socket()){
                        s.connect(new InetSocketAddress("127.0.0.1",45789),2500);s.setTcpNoDelay(true);socket=s;
                        BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));
                        PrintWriter out=new PrintWriter(new OutputStreamWriter(s.getOutputStream()),true);
                        out.println("HELLO "+code);
                        s.setSoTimeout(3500);
                        String response=in.readLine();
                        if(!"OK".equals(response)){setStatus("Неверный PIN или отказ Windows");networkRunning=false;break;}
                        s.setSoTimeout(0);outgoing.clear();mouseConnected=true;setStatus("Подключено по USB · акселерометр");
                        while(networkRunning&&!s.isClosed()){
                            String item=outgoing.take();out.println(item);if(out.checkError())throw new IOException("Network error");
                        }
                    }
                }catch(Exception ex){if(networkRunning)setStatus("USB: нет связи. Проверь USB-отладку, ADB reverse и Windows: "+ex.getMessage());}
                finally{mouseConnected=false;socket=null;outgoing.clear();}
                if(networkRunning){try{Thread.sleep(1500);}catch(InterruptedException ignored){}}
            }
            if(running&&!networkRunning)runOnUiThread(this::stopEverything);
        },"network");socketThread.start();
    }
    // Experimental inertial motion detection. It cannot recover accurate position:
    // acceleration is high-pass filtered, integrated briefly, and velocity decays.
    // Keep phone flat; movement is estimated, not tracked like a real mouse.
    private synchronized void resetMotion(){
        gravityReady=false;previousSensorNs=0;velocityX=velocityY=0;
        residualX=residualY=0;filtered[0]=filtered[1]=0;
        bias[0]=bias[1]=0;stillSamples=0;
    }
    private void startMotion(){
        resetMotion();sensorEvents=0;lastReportNs=0;
        motionListener=new SensorEventListener(){
            @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
            @Override public void onSensorChanged(SensorEvent e){processAcceleration(e);}
        };
        boolean registered=sensorManager.registerListener(motionListener,accelerometer,SensorManager.SENSOR_DELAY_FASTEST);
        if(!registered)setStatus("Не удалось запустить акселерометр");
    }
    private synchronized void processAcceleration(SensorEvent e){
        if(!running)return;
        long now=e.timestamp;
        if(!gravityReady){System.arraycopy(e.values,0,gravity,0,3);gravityReady=true;previousSensorNs=now;return;}
        float dt=(now-previousSensorNs)*1.0e-9f;previousSensorNs=now;
        if(dt<=0||dt>0.10f){velocityX=velocityY=0;return;}
        // Slow gravity estimate; rejects most stationary tilt and DC bias.
        float alpha=(float)Math.exp(-dt/0.50);
        for(int i=0;i<3;i++)gravity[i]=alpha*gravity[i]+(1-alpha)*e.values[i];
        // Screen faces upward: physical right maps to X, forward/back to Y.
        float ax=e.values[0]-gravity[0]-bias[0];
        float ay=e.values[1]-gravity[1]-bias[1];
        float lp=(float)Math.exp(-dt/0.024);
        filtered[0]=lp*filtered[0]+(1-lp)*ax;
        filtered[1]=lp*filtered[1]+(1-lp)*ay;
        double magnitude=Math.hypot(filtered[0],filtered[1]);
        // Small accelerations are noise. No physical motion can be inferred
        // reliably from this value alone.
        if(magnitude<0.18){stillSamples++;}
        else stillSamples=0;
        if(stillSamples>9){velocityX=velocityY=0;residualX=residualY=0;}
        else {
            double gain=Math.max(0.15,sensitivityValue/85.0);
            double dead=0.12;
            double vx=Math.abs(filtered[0])>dead ? Math.copySign(Math.abs(filtered[0])-dead,filtered[0]) : 0;
            double vy=Math.abs(filtered[1])>dead ? Math.copySign(Math.abs(filtered[1])-dead,filtered[1]) : 0;
            // Integrate momentarily and leak velocity. Damp accumulated drift.
            double decay=Math.exp(-dt/0.115);
            velocityX=velocityX*decay+vx*dt*650*gain;
            velocityY=velocityY*decay+vy*dt*650*gain;
            velocityX=Math.max(-900,Math.min(900,velocityX));
            velocityY=Math.max(-900,Math.min(900,velocityY));
            double sign=invert?-1:1;
            residualX+=sign*velocityX*dt;
            residualY+=sign*velocityY*dt;
            int dx=(int)residualX,dy=(int)residualY;
            residualX-=dx;residualY-=dy;
            if(mouseConnected && (dx!=0||dy!=0))send("M "+Math.max(-100,Math.min(100,dx))+" "+Math.max(-100,Math.min(100,dy)));
        }
        sensorEvents++;
        if(now-lastReportNs>1_000_000_000L){
            int count=sensorEvents;sensorEvents=0;lastReportNs=now;
            String info="Акселерометр: "+count+" Гц · ускорение: "+String.format(java.util.Locale.US,"%.2f",magnitude)+" м/с²";
            runOnUiThread(()->quality.setText(info));
        }
    }
    private void stopEverything(){
        running=false;networkRunning=false;mouseConnected=false;outgoing.clear();
        if(motionListener!=null){sensorManager.unregisterListener(motionListener);motionListener=null;}
        resetMotion();
        if(socket!=null)try{socket.close();}catch(Exception ignored){}
        if(socketThread!=null)socketThread.interrupt();
        runOnUiThread(()->{startButton.setText("ПОДКЛЮЧИТЬ");status.setText("Остановлено");quality.setText("Акселерометр выключен");});
    }
    @Override protected void onDestroy(){stopEverything();super.onDestroy();}
}
