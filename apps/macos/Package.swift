// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "Hoppair", platforms: [.macOS(.v14)],
    products: [.executable(name: "Hoppair", targets: ["Hoppair"])],
    targets: [
        .target(name: "HoppairCore"),
        .executableTarget(name: "Hoppair", dependencies: ["HoppairCore"]),
        .testTarget(name: "HoppairCoreTests", dependencies: ["HoppairCore"])
    ]
)
