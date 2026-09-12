dependencies {
	// MangaSourceClient's methods are suspend, so the coroutines API is part of this module's
	// public surface rather than an implementation detail.
	api(libs.kotlinx.coroutines.core)

	// For ParserBridge.imageHttpClient, which is in this module's public surface for the same
	// reason coroutines are. OkHttp rather than a wrapper of our own because that is precisely
	// what the classloader boundary can carry: `okhttp3.` is parent-first in ParsersClassLoader,
	// so the client the child hands back is the same Class the parent already holds. An Ageha
	// type here would be a box whose only content is an OkHttpClient.
	api(libs.okhttp)
}
