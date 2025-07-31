.PHONY: debug release clean

debug:
	nix-shell -p openjdk17 --command "ANDROID_HOME=$(PWD)/android-sdk ./gradlew assembleDebug"

release:
	nix-shell -p openjdk17 --command "ANDROID_HOME=$(PWD)/android-sdk ./gradlew assembleRelease"

clean:
	nix-shell -p openjdk17 --command "ANDROID_HOME=$(PWD)/android-sdk ./gradlew clean"