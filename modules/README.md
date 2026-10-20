# Modules

Three small Android TV apps that show up on the CleanXiaomiTV home screen as **module cards**
(see [`docs/modules.md`](../docs/modules.md)). Weather and Prayer Times are about 170 KB and Channels about 750 KB (it includes a video player).
None of them runs in the background, and each uses the network only to fetch its own data.

| | |
|---|---|
| ![Weather](docs/weather.png) | ![Prayer Times](docs/prayer.png) |
| **Weather**: today and a 7-day forecast from [Open-Meteo](https://open-meteo.com). **Change city** searches any city with the remote; **Refresh** reloads. | **Prayer Times**: today's times from [Aladhan](https://aladhan.com) (Morocco method), with the next prayer and a countdown. |
| ![The channel list over a channel playing](docs/channels.png) | ![A channel playing](docs/channels-playing.png) |
| **Channels**: 35 live channels (Moroccan, news, entertainment, documentary, religious) from free streams the broadcasters publish. It opens on the last channel; OK shows the channel list on the side (Left / Right jump between groups), Up / Down or CH+ / CH- switch channels. | The channel list is in `channels/src/main/res/raw/channels.m3u`; once a day the app takes fresh links and logos for the same channels from [iptv-org](https://github.com/iptv-org/iptv)'s public lists, matched by `tvg-id`. |

## Install

From the repo root, with the TV's IP:

```bash
cd modules && ./gradlew assembleRelease && cd ..
for m in weather prayer channels; do
  TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/b64push.py modules/$m/build/outputs/apk/release/$m-release.apk /data/local/tmp/$m.apk
  TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/tv.py "pm install -r /data/local/tmp/$m.apk; rm -f /data/local/tmp/$m.apk"
done
```

Press Home and the launcher asks once per app whether to show its card. Hold OK on Settings → Module cards to
change your answer.

The default city is Casablanca: `latitude`, `longitude` and `place` in each app's `src/main/res/values/config.xml`.
Prayer Times uses that city; Weather starts there and remembers whatever you pick with Change city.

Icons: [Material](https://fonts.google.com/icons) (Apache 2.0), [Feather](https://feathericons.com) (MIT) and
[Lucide](https://lucide.dev) (ISC). Font: Outfit (OFL, `FONT-LICENSE-OFL.txt`).
