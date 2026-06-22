// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "ARMusicIOSCore",
    platforms: [
        .iOS(.v15)
    ],
    products: [
        .library(
            name: "ARMusicIOSCore",
            targets: ["ARMusicIOSCore"]
        )
    ],
    targets: [
        .target(name: "ARMusicIOSCore"),
        .testTarget(
            name: "ARMusicIOSCoreTests",
            dependencies: ["ARMusicIOSCore"]
        )
    ]
)
