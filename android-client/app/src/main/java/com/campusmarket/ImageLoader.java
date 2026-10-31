package com.campusmarket;

import android.graphics.*;
import android.os.*;
import android.widget.ImageView;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

/** Bounded native loader. A view tag prevents recycled cards showing an old request. */
final class ImageLoader implements AutoCloseable {
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    void load(long id,String token,ImageView view){
        view.setTag(id);view.setImageResource(R.drawable.ic_launcher);
        executor.execute(()->{
            HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(BuildConfig.API_BASE_URL+"/images/"+id).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
                if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
                if(connection.getResponseCode()!=200)return;
                byte[] bytes;try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>5242880)throw new IOException("Image too large");out.write(buffer,0,n);}bytes=out.toByteArray();}
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(options.outWidth<1||options.outHeight<1||options.outWidth>4096||options.outHeight>4096)return;
                options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1000)options.inSampleSize*=2;options.inJustDecodeBounds=false;
                Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)return;
                handler.post(()->{if(!closed&&Long.valueOf(id).equals(view.getTag()))view.setImageBitmap(bitmap);else bitmap.recycle();});
            }catch(IOException ignored){/* Keep placeholder; the enclosing page supports refresh. */}
            finally{if(connection!=null)connection.disconnect();}
        });
    }
    @Override public void close(){closed=true;executor.shutdownNow();handler.removeCallbacksAndMessages(null);}
}
