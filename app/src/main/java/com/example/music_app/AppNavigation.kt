package com.example.music_app

enum class AuthScreen {
    Login,
    Register,
    Code,
    App
}

enum class AppTab(val labelRes: Int, val icon: String) {
    Home(R.string.tab_home, "⌂"),
    Player(R.string.tab_player, "▷"),
    Equalizer(R.string.tab_equalizer, "☷"),
    Metronome(R.string.tab_metronome, "◴"),
    Library(R.string.tab_library, "▥"),
    Playlists(R.string.tab_playlists, "▤"),
    Profile(R.string.tab_profile, "♙")
}
