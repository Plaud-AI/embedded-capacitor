package ai.plaud.pwademo;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/**
 * Capacitor discovers plugins from the npm packages listed in capacitor.config.json, which
 * the CLI only populates for installed plugin packages. {@code PlaudSdk} is app-local (it
 * lives in this module, against libs/plaud-sdk.aar), so it isn't in that list and would
 * otherwise surface as "PlaudSdk plugin is not implemented on android".
 *
 * <p>Registering it before {@code super.onCreate()} makes it available to JS as soon as the
 * bridge comes up. This is the Android counterpart to iOS's
 * {@code MainViewController.capacitorDidLoad()}.
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PlaudSdkPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
