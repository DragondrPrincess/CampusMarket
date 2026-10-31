package com.campusmarket;

import android.app.*;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Compiled in phase 4; device execution requires separate user authorization. */
@RunWith(AndroidJUnit4.class)
public final class CatalogFlowTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    @Test public void browseSearchAndDetail()throws Exception{
        MainActivity main=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(()->onMain(()->((RecyclerView)main.findViewById(R.id.product_list)).getChildCount()>0),"database products displayed");
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(ProductDetailActivity.class.getName(),null,false);
        onMain(()->{((RecyclerView)main.findViewById(R.id.product_list)).getChildAt(0).performClick();return null;});
        ProductDetailActivity detail=(ProductDetailActivity)instrumentation.waitForMonitorWithTimeout(monitor,5000);instrumentation.removeMonitor(monitor);
        assertNotNull("detail activity opened",detail);
        await(()->onMain(()->detail.findViewById(R.id.detail_content).getVisibility()==View.VISIBLE),"detail read from server");
        assertFalse(onMain(()->((TextView)detail.findViewById(R.id.detail_description)).getText().toString()).isEmpty());
        onMain(()->{detail.finish();return null;});instrumentation.waitForIdleSync();
        onMain(()->{((EditText)main.findViewById(R.id.product_keyword)).setText("no_such_item_2f33d18f");main.findViewById(R.id.product_search).performClick();return null;});
        await(()->onMain(()->((TextView)main.findViewById(R.id.catalog_status)).getText().toString().equals(main.getString(R.string.catalog_empty))),"empty state displayed");
        onMain(()->{((EditText)main.findViewById(R.id.product_keyword)).setText("高等数学");main.findViewById(R.id.product_search).performClick();return null;});
        await(()->onMain(()->((RecyclerView)main.findViewById(R.id.product_list)).getAdapter().getItemCount()>0),"Chinese search displayed");
    }
    private <T>T onMain(Callable<T> action){AtomicReference<T> result=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{result.set(action.call());}catch(Exception e){throw new AssertionError("UI action failed");}});return result.get();}
    private void await(BooleanSupplier condition,String label)throws InterruptedException{for(int i=0;i<150;i++){if(condition.getAsBoolean())return;Thread.sleep(100);}fail(label);}
}
