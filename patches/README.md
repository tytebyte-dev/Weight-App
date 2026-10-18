# Patches for the MediaServiceCore submodule

`MediaServiceCore` is a git submodule that points at someone else's repository, so changes to it
cannot be committed in this repo. Patches the app depends on live here.

## 0001 — sign-in with a system Google account (microG / Google Play Services)

Required by `MobileSignInActivity` ("Sign in with a device Google account"): adds
`GmsAccountAuth`, `YouTubeSignInService.signInWithGmsAccountObserve()` and token retrieval through
`AccountManager`. Without it the app does not compile.

Apply once after checking out the submodules:

    git -C MediaServiceCore am ../patches/0001-mediaservicecore-sign-in-with-system-google-account.patch

(Or `git apply` if you do not want a commit inside the submodule.)
