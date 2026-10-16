import SwiftUI

@main
struct TokenUsageAssistantApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var state = AppState.shared

    var body: some Scene {
        MenuBarExtra {
            MenuBarView().environmentObject(state)
        } label: {
            Image(systemName: "chart.bar.fill")
        }
        .menuBarExtraStyle(.window)
    }
}