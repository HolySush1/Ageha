dependencies {
	// MangaSourceClient's methods are suspend, so the coroutines API is part of this module's
	// public surface rather than an implementation detail.
	api(libs.kotlinx.coroutines.core)
}
