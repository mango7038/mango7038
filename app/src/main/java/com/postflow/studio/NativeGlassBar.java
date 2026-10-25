package com.postflow.studio;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

/**
 * Android GPU-rendered glass navigation. API 33+ uses AGSL for refractive edge
 * lighting; API 26-32 uses a high-contrast gradient fallback. This shader
 * operates on the bar's own surface, not a capture of HTML WebView pixels.
 */
public final class NativeGlassBar extends FrameLayout {
    private static final String[] TITLES={"Home","Calendar","Create","Library","More"};
    private final Activity host;
    private final TextView[] tabs=new TextView[5];
    private int selected=0;
    private final Surface surface;
    private final float density;
    public interface Listener {void onTab(int index);}
    public NativeGlassBar(Activity activity,Listener listener){
        super(activity);host=activity;density=getResources().getDisplayMetrics().density;
        setClipChildren(false);setClipToPadding(false);
        surface=new Surface(activity);
        addView(surface,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout items=new LinearLayout(activity);items.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(-1,-1);
        ip.leftMargin=dp(5);ip.rightMargin=dp(5);
        addView(items,ip);
        for(int i=0;i<5;i++){
            final int n=i;
            TextView tab=new TextView(activity);tabs[i]=tab;
            tab.setText(TITLES[i]);tab.setTextSize(11);
            tab.setGravity(Gravity.CENTER);tab.setSingleLine(true);
            tab.setContentDescription(TITLES[i]);tab.setFocusable(true);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1f);
            lp.setMargins(dp(2),dp(7),dp(2),dp(7));
            items.addView(tab,lp);
            tab.setOnClickListener(v->{
                tab.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                select(n);listener.onTab(n);
            });
        }
        select(0);
    }
    private int dp(float v){return Math.round(v*density);}
    public void select(int index){
        if(index<0||index>=tabs.length)return;
        selected=index;
        for(int i=0;i<tabs.length;i++){
            final boolean active=i==index;
            tabs[i].setTextColor(active?Color.WHITE:0xffb9bfd3);
            GradientDrawable pill=new GradientDrawable();
            pill.setColor(active?0xcc655ac9:Color.TRANSPARENT);
            pill.setCornerRadius(dp(19));
            if(active)pill.setStroke(dp(1),0x40ffffff);
            tabs[i].setBackground(pill);
            tabs[i].animate().scaleX(active?1.03f:1f).scaleY(active?1.03f:1f).setDuration(220).start();
        }
    }
    private static final class Surface extends View {
        private final Paint paint=new Paint(3);
        private RuntimeShader glass;
        private final float density;
        Surface(Activity a){
            super(a);density=getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_HARDWARE,null);
            if(Build.VERSION.SDK_INT>=33){
                try{
                    glass=new RuntimeShader(
                       "uniform float2 size; half4 main(float2 p){"+
                       "float2 uv=p/size; float e=min(min(uv.x,1.-uv.x),min(uv.y,1.-uv.y));"+
                       "float rim=exp(-65.*e); float wave=sin((uv.x+uv.y*.4)*13.)*.012;"+
                       "half3 base=half3(.12,.16,.28)+half3(.11,.08,.21)*(1.-uv.y);"+
                       "half3 halo=half3(.30,.45,.80)*exp(-10.*length(uv-half2(.22,.0)));"+
                       "half dispersion=half(sin(uv.x*18.+uv.y*7.)*.02);"+
                       "half3 light=half3(.35+dispersion,.43,.64-dispersion)*half(rim);"+
                       "return half4(base+halo*.55+light*.48+half(wave*.3),.985);}");
                }catch(Exception ignored){glass=null;}
            }
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float r=28*density;
            if(Build.VERSION.SDK_INT>=33 && glass!=null){
                glass.setFloatUniform("size",(float)Math.max(1,getWidth()),(float)Math.max(1,getHeight()));
                paint.setShader(glass);
            }else{
                paint.setShader(new LinearGradient(0,0,getWidth(),getHeight(),new int[]{0xfa252a45,0xfd141a30},null,Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(0,0,getWidth(),getHeight(),r,r,paint);
            paint.setShader(null);
            paint.setColor(0x66ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);
            canvas.drawRoundRect(density,density,getWidth()-density,getHeight()-density,r,r,paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}