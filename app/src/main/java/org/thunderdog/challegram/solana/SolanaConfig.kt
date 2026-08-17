package org.thunderdog.challegram.solana

/**
 * Central Solana network configuration for human-gram's wallet/video-hook code.
 * Matches config/program.config.json -> networks.devnet.
 */
object SolanaConfig {
    const val RPC_URL: String = "https://api.devnet.solana.com"
    const val CLUSTER: String = "devnet"
    const val ONDO_ZERO_REGISTRY_PROGRAM_ID: String = "7in5g9wjnBkW43MdSRUGc1QUUEyfsiACohP8NfBgbKQ5"

    // config/program.config.json -> networks.devnet
    const val PRC_MINT: String = "ByDncPjp2cyDW9JNubG8jpeX8ct7XzoHojYiSbegKZBC"
    const val TRANSFER_HOOK_PROGRAM_ID: String = "5TSJVZmogVDWEN1K3SpyW5asNaevLf5R64iecPeKN4up"
    const val ONDO_ZERO_GUARDIAN_PUBKEY: String = "DBFdn3gwsk11pXzVnAnVZbrtW9U4Xp9LMVnmzFF5rYC6"

    // PRC token decimals (config/program.config.json -> tokenLaunch.decimals)
    const val PRC_DECIMALS: Int = 9

    // Display name fallback when on-chain token metadata is unavailable
    const val UTILITY_TOKEN_FALLBACK_NAME: String = "PRC"
}
