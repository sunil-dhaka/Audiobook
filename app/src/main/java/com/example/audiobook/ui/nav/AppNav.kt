package com.example.audiobook.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.audiobook.ui.screens.library.LibraryScreen
import com.example.audiobook.ui.screens.player.PlayerScreen

object Routes {
    const val LIBRARY = "library"
    const val PLAYER = "player/{bookId}"
    fun player(bookId: String) = "player/${Uri.encode(bookId)}"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBookClick = { id -> nav.navigate(Routes.player(id)) },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: ""
            PlayerScreen(
                bookId = bookId,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
