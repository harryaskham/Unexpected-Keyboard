.PHONY: debug release clean install

debug:
	nix develop --command gradle assembleDebug

release:
	nix develop --command gradle assembleRelease

clean:
	nix develop --command gradle clean

install: debug
	adb connect samsung-sm-s928b:5555 || true
	adb -e install -r build/outputs/apk/debug/juloo.keyboard2.fork.debug.apk
