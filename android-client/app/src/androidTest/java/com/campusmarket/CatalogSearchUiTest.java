package com.campusmarket;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Read-only API/UI regression; no accounts, products, orders or device settings changed. */
@RunWith(AndroidJUnit4.class)
public final class CatalogSearchUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private MainActivity main;
    @Test public void compositionPasteSearchPaginationAndLayout() throws Exception {
        main=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(()->count()==6,"initial page");
        snapshot("home-after");
        onMain(()->{main.findViewById(R.id.catalog_more).performClick();return null;});
        await(()->count()==8,"load more retains first page");
        onMain(()->{
            EditText field=main.findViewById(R.id.product_keyword);field.requestFocus();
            EditorInfo info=new EditorInfo();InputConnection input=field.onCreateInputConnection(info);
            assertEquals(android.text.InputType.TYPE_CLASS_TEXT,info.inputType & android.text.InputType.TYPE_MASK_CLASS);
            assertEquals(0,info.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII);
            assertEquals("zh-CN",info.hintLocales.get(0).toLanguageTag());
            assertTrue(input.setComposingText("高等",1));assertTrue(input.setComposingText("高等数学",1));assertTrue(input.finishComposingText());
            assertEquals("高等数学",field.getText().toString());
            assertTrue(input.performEditorAction(EditorInfo.IME_ACTION_SEARCH));return null;
        });
        await(()->count()==1 && firstTitle().contains("高等数学"),"Chinese IME composition search");
        snapshot("chinese-search");
        search("no_such_English_item_2f33d18f");
        await(()->onMain(()->((TextView)main.findViewById(R.id.catalog_status)).getText().toString().equals(main.getString(R.string.catalog_empty))),"English search empty state");
        ClipboardManager clipboard=(ClipboardManager)main.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData saved=onMain(clipboard::getPrimaryClip);
        try {
            onMain(()->{clipboard.setPrimaryClip(ClipData.newPlainText("test keyword","无线鼠标"));main.findViewById(R.id.product_paste).performClick();
                assertEquals("无线鼠标",((EditText)main.findViewById(R.id.product_keyword)).getText().toString());main.findViewById(R.id.product_search).performClick();return null;});
            await(()->count()==1 && firstTitle().contains("无线鼠标"),"Chinese paste search");
        } finally {onMain(()->{if(saved!=null)clipboard.setPrimaryClip(saved);else clipboard.clearPrimaryClip();return null;});}
        Thread.sleep(3500); // Allow Android clipboard-access notification to disappear before screenshots.
        search("");await(()->count()==6,"reset search");
        String lowestPrice="¥"+new com.campusmarket.network.CatalogApi().list(1,0,"","price_asc").items[0].price;
        onMain(()->{((Spinner)main.findViewById(R.id.product_sort)).setSelection(1);return null;});
        await(()->count()>0 && onMain(()->((TextView)((RecyclerView)main.findViewById(R.id.product_list)).getChildAt(0).findViewById(R.id.product_price)).getText().toString().equals(lowestPrice)),"price sort");
        onMain(()->{((Spinner)main.findViewById(R.id.product_category)).setSelection(1);return null;});
        await(()->count()==2,"category filter");
        onMain(()->{((Spinner)main.findViewById(R.id.product_category)).setSelection(0);((Spinner)main.findViewById(R.id.product_sort)).setSelection(0);return null;});
        await(()->count()==6,"filters reset");
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(ProductDetailActivity.class.getName(),null,false);
        onMain(()->{((RecyclerView)main.findViewById(R.id.product_list)).getChildAt(0).performClick();return null;});
        Activity detail=instrumentation.waitForMonitorWithTimeout(monitor,5000);instrumentation.removeMonitor(monitor);assertNotNull(detail);
        await(()->onMain(()->detail.findViewById(R.id.detail_content).getVisibility()==View.VISIBLE),"detail loads");
        onMain(()->{detail.finish();return null;});await(()->count()==6,"return to catalog");
        onMain(()->{main.findViewById(R.id.open_diagnostics).performClick();main.findViewById(R.id.check_connection).performClick();return null;});
        await(()->onMain(()->((TextView)main.findViewById(R.id.connection_status)).getText().toString().equals(main.getString(R.string.connected))),"database connection");
        onMain(()->{main.findViewById(R.id.back_catalog).performClick();((RadioGroup)main.findViewById(R.id.navigation)).check(R.id.nav_profile);return null;});
        assertEquals(View.VISIBLE,(int)onMain(()->main.findViewById(R.id.profile_panel).getVisibility()));
        onMain(()->{((RadioGroup)main.findViewById(R.id.navigation)).check(R.id.nav_home);return null;});
        // App-only orientation override, restored before finishing. Emulator configuration is untouched.
        onMain(()->{main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);return null;});
        await(()->currentMain()!=null && currentMain().getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,"landscape");
        main=currentMain();await(()->count()==6,"landscape products");assertUsable();snapshot("landscape-after");
        onMain(()->{main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);return null;});
        await(()->currentMain()!=null && currentMain().getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT,"portrait restored");
        main=currentMain();await(()->count()==6,"portrait products");assertUsable();snapshot("home-after");
    }
    private MainActivity currentMain(){return onMain(()->{
        for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)return (MainActivity)a;
        return null;
    });}
    private void assertUsable(){onMain(()->{assertTrue(main.findViewById(R.id.product_list).getHeight()>80*main.getResources().getDisplayMetrics().density);
        for(int id:new int[]{R.id.product_search,R.id.product_paste,R.id.product_category,R.id.product_sort,R.id.nav_home,R.id.nav_profile})assertTrue(main.findViewById(id).getHeight()>=48*main.getResources().getDisplayMetrics().density);return null;});}
    private int count(){return onMain(()->((RecyclerView)main.findViewById(R.id.product_list)).getAdapter().getItemCount());}
    private String firstTitle(){return onMain(()->{RecyclerView list=main.findViewById(R.id.product_list);return list.getChildCount()==0?"":((TextView)list.getChildAt(0).findViewById(R.id.product_title)).getText().toString();});}
    private void search(String text){onMain(()->{((EditText)main.findViewById(R.id.product_keyword)).setText(text);main.findViewById(R.id.product_search).performClick();return null;});}
    private void snapshot(String name)throws Exception{instrumentation.waitForIdleSync();Thread.sleep(300);Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(OutputStream out=new FileOutputStream(new File(main.getExternalFilesDir(null),name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    private <T>T onMain(Callable<T> action){AtomicReference<T> result=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{result.set(action.call());}catch(Exception e){throw new AssertionError(e);}});return result.get();}
    private void await(BooleanSupplier condition,String label)throws InterruptedException{for(int i=0;i<150;i++){if(condition.getAsBoolean())return;Thread.sleep(100);}fail(label);}
}
