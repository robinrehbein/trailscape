package io.github.robinrehbein.trailscape

import io.flutter.embedding.android.FlutterFragmentActivity

// FlutterFragmentActivity statt FlutterActivity: Das health-Plugin fragt die
// Health-Connect-Berechtigungen ueber registerForActivityResult an und braucht
// dafuer eine ComponentActivity (Vorgabe aus dem README des Pakets).
class MainActivity : FlutterFragmentActivity()
