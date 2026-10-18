# Patches for the MediaServiceCore submodule

`MediaServiceCore` is a git submodule that points at someone else's repository, so changes to it
cannot be committed in this repo. Patches the app depends on live here.

## 0001 — sign-in with a system Google account (microG / Google Play Services)

Required by `MobileSignInActivity` ("Sign in with a device Google account"): adds
`GmsAccountAuth`, `YouTubeSignInService.signInWithGmsAccountObserve()` and token retrieval through
`AccountManager`. Without it the app does not compile.

## 0002 — account types of microG builds

microG RE keeps its accounts under its own account type (`app.revanced`, or `app.morphe`), not
`com.google`, so the picker used to show only the Google account of the device and never the microG
one. The picker is now limited to the installed microG build's type (falling back to `com.google`),
and the account type is stored in the account key (`gms:<hex type>:<hex name>`; old keys without a type
still mean `com.google`). Needs 0001.

Apply in order after checking out the submodules (CI does this):

    for p in patches/*.patch; do git -C MediaServiceCore apply "../$p"; done

(Or `git am` if you want commits inside the submodule.)
