// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TokenUsageAssistant",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "TokenUsageAssistant",
            path: "Sources/TokenUsageAssistant",
            resources: [.copy("Resources/adapters")]
        )
    ]
)