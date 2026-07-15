import Capacitor
import PlaudPlugin

/// Capacitor 8 registers plugins from `capacitor.config.json`'s `packageClassList`,
/// which the CLI only populates for npm-installed plugins. `PlaudSdk` lives in the
/// local `PlaudPlugin` SwiftPM package, so it isn't in that list and would otherwise
/// surface as "PlaudSdk plugin is not implemented on iOS".
///
/// `capacitorDidLoad()` runs right after the bridge finishes auto-registration and
/// before the web content loads, so registering the instance here makes `PlaudSdk`
/// available to JS. This is the documented hook for app-local plugins; wired up via
/// `customClass` on the Bridge View Controller in Main.storyboard.
class MainViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(PlaudSdkPlugin())
    }
}
