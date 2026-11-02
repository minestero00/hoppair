// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "MacDroid", platforms: [.macOS(.v14)],
    products: [.executable(name: "MacDroid", targets: ["MacDroid"])],
    targets: [
        .target(name: "MacDroidCore"),
        .executableTarget(name: "MacDroid", dependencies: ["MacDroidCore"]),
        .testTarget(name: "MacDroidCoreTests", dependencies: ["MacDroidCore"])
    ]
)
