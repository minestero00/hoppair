// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "Hoppair", platforms: [.macOS(.v14)],
    products: [.executable(name: "Hoppair", targets: ["Hoppair"])],
    dependencies: [.package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.0")],
    targets: [
        .target(name: "HoppairCore"),
        .executableTarget(name: "Hoppair", dependencies: ["HoppairCore", .product(name: "Sparkle", package: "Sparkle")],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"])]),
        .testTarget(name: "HoppairCoreTests", dependencies: ["HoppairCore"])
    ]
)
