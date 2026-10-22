import Foundation

// «Testen» des eigenen Bitcoin-Servers (Teil von `WatchedAddressSource`): gleiche Sitzung wie die Adressabfragen
// (neutraler User-Agent, flüchtig, nicht im HTTP-Protokoll) — wie `WatchedAddressSourceServer.kt`.

extension WatchedAddressSource {
    /// Blockhöhe vom eigenen Server (`AddressServer.testUrl`, nennt keine Adresse) oder nil, wenn er nicht passend antwortet.
    static func testBtcServer(_ server: String) async -> Int64? {
        guard let body = try? await load(AddressServer.testUrl(server), post: nil) else { return nil }
        return AddressServer.parseTipHeight(body)
    }
}
