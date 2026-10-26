package com.postflow.studio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Convert a source photo into a 1080x1920 Story upload while preserving the
 * entire original poster. The source File is NEVER written to or renamed.
 * Explicit "fill_crop" is the only mode that may crop the source.
 */
public final class StoryCanvasRenderer {
    private StoryCanvasRenderer(){}
    static final int WIDTH=1080, HEIGHT=1920;
    static final String DEFAULT_STYLE="fit_blur";

    static String safeStyle(String style){
        if("fit_solid".equals(style)||"fit_gradient".equals(style)||"fill_crop".equals(style))return style;
        return DEFAULT_STYLE;
    }
    private static Bitmap decode(File file)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Cannot decode Story photo");
        BitmapFactory.Options o=new BitmapFactory.Options();
        o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        o.inSampleSize=1;
        while(bounds.outWidth/o.inSampleSize>2500||bounds.outHeight/o.inSampleSize>2500)o.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),o);
        if(bitmap==null)throw new IOException("Invalid Story image");
        // EXIF rotation is critical for phone-camera photos.
        try{
            ExifInterface exif=new ExifInterface(file.getAbsolutePath());
            int orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix=new Matrix();
            if(orientation==ExifInterface.ORIENTATION_ROTATE_90)matrix.postRotate(90);
            else if(orientation==ExifInterface.ORIENTATION_ROTATE_180)matrix.postRotate(180);
            else if(orientation==ExifInterface.ORIENTATION_ROTATE_270)matrix.postRotate(270);
            else if(orientation==ExifInterface.ORIENTATION_FLIP_HORIZONTAL)matrix.postScale(-1,1);
            else if(orientation==ExifInterface.ORIENTATION_FLIP_VERTICAL)matrix.postScale(1,-1);
            else if(orientation==ExifInterface.ORIENTATION_TRANSPOSE){matrix.postScale(-1,1);matrix.postRotate(90);}
            else if(orientation==ExifInterface.ORIENTATION_TRANSVERSE){matrix.postScale(-1,1);matrix.postRotate(270);}
            if(!matrix.isIdentity()){
                Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
                if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;}
            }
        }catch(Exception ignored){/* non-JPEG / absent EXIF */}
        return bitmap;
    }
    private static int darken(int color,float amount){
        return Color.rgb(Math.round(Color.red(color)*amount),Math.round(Color.green(color)*amount),Math.round(Color.blue(color)*amount));
    }
    private static int average(Bitmap b,int startY,int endY){
        long r=0,g=0,bl=0,n=0;
        for(int y=startY;y<endY;y+=Math.max(1,(endY-startY)/25)){
            for(int x=0;x<b.getWidth();x+=Math.max(1,b.getWidth()/25)){
                int c=b.getPixel(x,y);r+=Color.red(c);g+=Color.green(c);bl+=Color.blue(c);n++;
            }
        }
        return n==0?Color.rgb(23,29,48):Color.rgb((int)(r/n),(int)(g/n),(int)(bl/n));
    }
    private static void blurredBackdrop(Canvas canvas,Bitmap src,Paint paint){
        Bitmap tiny=Bitmap.createScaledBitmap(src,48,85,true);
        int w=tiny.getWidth(),h=tiny.getHeight();
        int[] pixels=new int[w*h],out=new int[w*h];
        tiny.getPixels(pixels,0,w,0,0,w,h);
        // Two inexpensive box-blur passes at thumbnail resolution.
        for(int pass=0;pass<3;pass++){
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                long rr=0,gg=0,bb=0,count=0;
                for(int yy=Math.max(0,y-5);yy<=Math.min(h-1,y+5);yy++)
                    for(int xx=Math.max(0,x-5);xx<=Math.min(w-1,x+5);xx++){
                        int c=pixels[yy*w+xx];rr+=Color.red(c);gg+=Color.green(c);bb+=Color.blue(c);count++;
                    }
                out[y*w+x]=Color.rgb((int)(rr/count),(int)(gg/count),(int)(bb/count));
            }
            int[] swap=pixels;pixels=out;out=swap;
        }
        tiny.setPixels(pixels,0,w,0,0,w,h);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(tiny,null,new android.graphics.Rect(0,0,WIDTH,HEIGHT),paint);
        tiny.recycle();
        paint.setColor(0x750d1020);
        canvas.drawRect(0,0,WIDTH,HEIGHT,paint);
        paint.setColor(Color.WHITE);
    }
    static File render(File original,File cacheDir,String style)throws IOException{
        String mode=safeStyle(style);
        Bitmap source=decode(original);
        Bitmap output=null;
        File result=null;
        try{
            output=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(output);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            int top=average(source,0,Math.max(1,source.getHeight()/3));
            int bottom=average(source,source.getHeight()*2/3,source.getHeight());
            if("fit_blur".equals(mode))blurredBackdrop(canvas,source,p);
            else if("fit_gradient".equals(mode)){
                p.setShader(new LinearGradient(0,0,WIDTH,HEIGHT,new int[]{darken(top,.65f),darken(bottom,.32f)},null,Shader.TileMode.CLAMP));
                canvas.drawRect(0,0,WIDTH,HEIGHT,p);p.setShader(null);
            }else{
                p.setColor(darken(average(source,0,source.getHeight()),.50f));
                canvas.drawRect(0,0,WIDTH,HEIGHT,p);
            }
            p.setColor(Color.WHITE);
            boolean crop="fill_crop".equals(mode);
            // Safe area protects top username/progress and bottom reply controls.
            float boxW=crop?WIDTH:WIDTH-88,boxH=crop?HEIGHT:HEIGHT-440;
            float scale=(crop?Math.max(boxW/source.getWidth(),boxH/source.getHeight()):Math.min(boxW/source.getWidth(),boxH/source.getHeight()));
            float imageW=source.getWidth()*scale,imageH=source.getHeight()*scale;
            canvas.save();
            if(crop)canvas.clipRect(0,0,WIDTH,HEIGHT);
            canvas.drawBitmap(source,null,new android.graphics.RectF((WIDTH-imageW)/2,(HEIGHT-imageH)/2,(WIDTH+imageW)/2,(HEIGHT+imageH)/2),p);
            canvas.restore();
            result=File.createTempFile("pf_story_",".jpg",cacheDir);
            try(FileOutputStream out=new FileOutputStream(result)){
                if(!output.compress(Bitmap.CompressFormat.JPEG,94,out))throw new IOException("Story JPEG creation failed");
                out.flush();
            }
            return result;
        }catch(IOException|RuntimeException ex){if(result!=null)result.delete();throw ex;}
        finally{source.recycle();if(output!=null)output.recycle();}
    }
}
