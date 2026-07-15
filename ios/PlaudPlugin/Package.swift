// swift-tools-version: 5.9
import PackageDescription

// Local Capacitor plugin that bridges the JS/WebView world to Plaud's native iOS
// SDK. The three precompiled Plaud frameworks are wrapped as binary targets so
// SwiftPM embeds + code-signs them into the app automatically (no manual
// "Embed Frameworks" build phase). This package is added to the App target the
// same way `CapApp-SPM` is, so it survives `npx cap sync`.
let package = Package(
    name: "PlaudPlugin",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "PlaudPlugin", targets: ["PlaudPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.4.1")
    ],
    targets: [
        .binaryTarget(name: "PlaudBleSDK", path: "Frameworks/PlaudBleSDK.xcframework"),
        .binaryTarget(name: "PlaudWiFiSDK", path: "Frameworks/PlaudWiFiSDK.xcframework"),
        .binaryTarget(name: "PlaudDeviceBasicSDK", path: "Frameworks/PlaudDeviceBasicSDK.xcframework"),
        .target(
            name: "PlaudPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                "PlaudBleSDK",
                "PlaudWiFiSDK",
                "PlaudDeviceBasicSDK"
            ]
        )
    ]
)
