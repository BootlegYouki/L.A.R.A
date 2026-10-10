# Default ProGuard/R8 rules for the L.A.R.A app module.
# Release is not minified yet (see build.gradle.kts); keep this file as the project hook
# so future shrinker configuration lives in one place.

# kotlinx.serialization keeps generated serializers via its own consumer rules shipped in the
# library; no extra keep rules are needed while minification is disabled.
