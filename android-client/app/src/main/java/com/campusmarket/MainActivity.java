package com.campusmarket;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.campusmarket.network.HealthClient;
import com.campusmarket.network.HealthResponse;
import com.campusmarket.network.UserApi;
import com.campusmarket.auth.SessionStore;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView details;
    private Button checkButton;
    private boolean destroyed;
    private boolean accountBusy;
    private SessionStore sessions;
    private RadioGroup navigation;
    private CatalogController catalog;
    private boolean active,unreadBusy;
    private final Runnable unreadPoll=new Runnable(){public void run(){if(!active)return;refreshUnread();mainHandler.postDelayed(this,10000);}};

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        UiInsets.apply(findViewById(R.id.root));
        status = findViewById(R.id.connection_status);
        details = findViewById(R.id.database_details);
        checkButton = findViewById(R.id.check_connection);
        checkButton.setOnClickListener(view -> checkConnection());
        catalog=new CatalogController(this);
        findViewById(R.id.open_diagnostics).setOnClickListener(v->showHome(true));
        findViewById(R.id.back_catalog).setOnClickListener(v->showHome(false));
        sessions = new SessionStore(this);
        findViewById(R.id.my_products).setOnClickListener(v->startActivity(new Intent(this,MyProductsActivity.class)));
        findViewById(R.id.buyer_orders).setOnClickListener(v->startActivity(new Intent(this,OrdersActivity.class)));
        findViewById(R.id.seller_orders).setOnClickListener(v->startActivity(new Intent(this,OrdersActivity.class).putExtra("seller",true)));
        findViewById(R.id.my_favorites).setOnClickListener(v->startActivity(new Intent(this,MyProductsActivity.class).putExtra("favorites",true)));
        findViewById(R.id.account_login).setOnClickListener(v -> startActivity(new Intent(this, AuthActivity.class)));
        findViewById(R.id.edit_profile).setOnClickListener(v -> startActivity(new Intent(this, EditProfileActivity.class)));
        findViewById(R.id.refresh_profile).setOnClickListener(v -> loadProfile());
        findViewById(R.id.logout).setOnClickListener(v -> logout());
        navigation = findViewById(R.id.navigation);
        navigation.setOnCheckedChangeListener((group, id) -> {
            if(id==R.id.nav_messages){startActivity(new Intent(this,sessions.token()==null?AuthActivity.class:ConversationsActivity.class));group.check(R.id.nav_home);return;}
            if(id==R.id.nav_publish){
                startActivity(new Intent(this,sessions.token()==null?AuthActivity.class:ProductEditActivity.class));
                group.check(R.id.nav_home);return;
            }
            boolean home = id == R.id.nav_home;
            boolean account = id == R.id.nav_profile;
            findViewById(R.id.catalog_panel).setVisibility(home ? View.VISIBLE : View.GONE);
            findViewById(R.id.secondary_scroll).setVisibility(home ? View.GONE : View.VISIBLE);
            findViewById(R.id.home_panel).setVisibility(View.GONE);
            findViewById(R.id.profile_panel).setVisibility(account ? View.VISIBLE : View.GONE);
            TextView placeholder = findViewById(R.id.placeholder);
            placeholder.setVisibility(home || account ? View.GONE : View.VISIBLE);
            if (account) loadProfile();
            if (!home && !account) {
                String title = ((TextView) findViewById(id)).getText().toString();
                placeholder.setText(getString(R.string.placeholder, title));
            }
        });
    }
    private void showHome(boolean diagnostics){
        findViewById(R.id.catalog_panel).setVisibility(diagnostics?View.GONE:View.VISIBLE);
        findViewById(R.id.secondary_scroll).setVisibility(diagnostics?View.VISIBLE:View.GONE);
        findViewById(R.id.home_panel).setVisibility(diagnostics?View.VISIBLE:View.GONE);
    }

    @Override protected void onResume() {
        super.onResume();
        active=true;refreshUnread();mainHandler.postDelayed(unreadPoll,10000);
        if(catalog!=null&&navigation!=null&&navigation.getCheckedRadioButtonId()==R.id.nav_home)catalog.refresh();
        if (navigation != null && navigation.getCheckedRadioButtonId() == R.id.nav_profile) loadProfile();
    }
    @Override protected void onPause(){active=false;mainHandler.removeCallbacks(unreadPoll);super.onPause();}
    private void refreshUnread(){if(unreadBusy||sessions==null)return;String token=sessions.token();TextView nav=findViewById(R.id.nav_messages);if(token==null){nav.setText(R.string.messages);return;}unreadBusy=true;
        executor.execute(()->{try{long count=new com.campusmarket.network.MessagingApi().unread(token);mainHandler.post(()->{if(destroyed)return;unreadBusy=false;if(sessions.token()!=null)nav.setText(count>0?getString(R.string.messages_badge,count):getString(R.string.messages));else nav.setText(R.string.messages);});}catch(IOException e){mainHandler.post(()->{if(!destroyed){unreadBusy=false;if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();nav.setText(R.string.messages);}}});}});
    }

    private void loadProfile() {
        if (accountBusy) return;
        boolean hadSession = sessions.hasSavedSession();
        String token = sessions.token();
        if (token == null) { signedOut(hadSession ? getString(R.string.session_expired) : getString(R.string.sign_in_prompt)); return; }
        accountBusy(true); accountStatus(getString(R.string.loading_profile));
        executor.execute(() -> {
            try {
                UserApi.User user = new UserApi().me(token);
                mainHandler.post(() -> { if (!destroyed) { accountBusy(false); showProfile(user); } });
            } catch (IOException exception) { accountError(exception); }
        });
    }

    private void showProfile(UserApi.User user) {
        accountStatus(getString(R.string.profile_welcome, user.nickname));
        TextView text = findViewById(R.id.profile_details);
        text.setText(getString(R.string.profile_format, user.username, filled(user.school), filled(user.campus), filled(user.bio)));
        text.setVisibility(View.VISIBLE);
        findViewById(R.id.account_login).setVisibility(View.GONE);
        for (int id : new int[]{R.id.edit_profile, R.id.refresh_profile, R.id.logout,R.id.my_products,R.id.my_favorites,R.id.buyer_orders,R.id.seller_orders}) findViewById(id).setVisibility(View.VISIBLE);
    }

    private String filled(String value) { return value == null || value.isEmpty() ? getString(R.string.not_filled) : value; }
    private void accountStatus(String message) { ((TextView) findViewById(R.id.account_status)).setText(message); }
    private void signedOut(String message) {
        accountStatus(message);
        ((TextView) findViewById(R.id.profile_details)).setText("");
        findViewById(R.id.profile_details).setVisibility(View.GONE);
        findViewById(R.id.account_login).setVisibility(View.VISIBLE);
        for (int id : new int[]{R.id.edit_profile, R.id.refresh_profile, R.id.logout,R.id.my_products,R.id.my_favorites,R.id.buyer_orders,R.id.seller_orders}) findViewById(id).setVisibility(View.GONE);
    }
    private void accountBusy(boolean value) {
        accountBusy = value;
        for (int id : new int[]{R.id.account_login, R.id.edit_profile, R.id.refresh_profile, R.id.logout}) findViewById(id).setEnabled(!value);
    }
    private void logout() {
        if (accountBusy) return;
        String token = sessions.token();
        if (token == null) { signedOut(getString(R.string.session_expired)); return; }
        accountBusy(true); accountStatus(getString(R.string.logging_out));
        executor.execute(() -> {
            try {
                new UserApi().logout(token);
                sessions.clear();
                mainHandler.post(() -> { if (!destroyed) { accountBusy(false); signedOut(getString(R.string.logged_out)); } });
            } catch (IOException exception) { accountError(exception); }
        });
    }
    private void accountError(IOException exception) {
        mainHandler.post(() -> {
            if (destroyed) return;
            accountBusy(false);
            if (exception instanceof UserApi.Failure) {
                UserApi.Failure failure = (UserApi.Failure) exception;
                if (failure.status == 401 || failure.status == 403) {
                    sessions.clear(); signedOut(getString(R.string.session_expired)); return;
                }
                accountStatus(failure.getMessage());
            } else {
                accountStatus(getString(R.string.account_network_error));
                findViewById(R.id.refresh_profile).setVisibility(View.VISIBLE);
            }
        });
    }

    private void checkConnection() {
        checkButton.setEnabled(false);
        status.setText(R.string.connecting);
        details.setText("");
        executor.execute(() -> {
            try {
                HealthResponse response = new HealthClient().fetch(BuildConfig.API_BASE_URL);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    HealthResponse.DatabaseInfo data = response.data;
                    status.setText(R.string.connected);
                    details.setText(getString(R.string.database_details, data.projectName, data.databaseName,
                            data.serverVersion, data.databaseTime, data.schemaVersion));
                    checkButton.setEnabled(true);
                });
            } catch (HealthClient.HttpStatusException exception) {
                showError(getString(R.string.server_error, exception.status));
            } catch (JsonParseException exception) {
                showError(getString(R.string.invalid_response));
            } catch (IOException exception) {
                showError(getString(R.string.connection_failed));
            }
        });
    }

    private void showError(String message) {
        mainHandler.post(() -> {
            if (destroyed) return;
            status.setText(message);
            checkButton.setEnabled(true);
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if(catalog!=null)catalog.close();
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
