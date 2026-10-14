"""Write the Music Assistant WebSocket fixtures used by the musicassistant module tests.

Messages are built from music-assistant-models 1.1.221, the version that
music-assistant/server 09e6bfb868b6a55e0f93afbb3356205948420d54 pins, and serialized
with the same to_json calls the server's websocket handler uses.

Run (PYTHONHASHSEED=0 is required: player features are sets, and the seed fixes their order):
PYTHONHASHSEED=0 uv run --python 3.13 --with music-assistant-models==1.1.221 \
    python scripts/podpocket/generate_ma_fixtures.py modules/features/musicassistant/src/test/resources/ma
"""

import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path

from music_assistant_models.api import (
    ErrorResultMessage,
    ServerInfoMessage,
    SuccessResultMessage,
)
from music_assistant_models.auth import User
from music_assistant_models.enums import (
    ContentType,
    EventType,
    MediaType,
    PlaybackState,
    PlayerFeature,
    PlayerType,
)
from music_assistant_models.event import MassEvent
from music_assistant_models.media_items import (
    AudioFormat,
    ItemMapping,
    PodcastEpisode,
    ProviderMapping,
    Track,
)
from music_assistant_models.player import DeviceInfo, Player, PlayerMedia
from music_assistant_models.player_queue import PlayerQueue
from music_assistant_models.queue_item import QueueItem

if os.environ.get("PYTHONHASHSEED") != "0":
    sys.exit("Set PYTHONHASHSEED=0 so set-valued fields serialize in a fixed order.")

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)

GPODDER = "gpodder--Xk3dTq9a"
FEED = "https://pod.example.com/feeds/show.xml"
GUID_A = "https://pod.example.com/feeds/ep-a"
ENC_A = "https://cdn.example.com/a.mp3"
GUID_B = "urn:uuid:7a4f3c1e-0b2d-4e8a-9c5f-2d1e3b4a5c6d"
ENC_B = "https://cdn.example.com/b.mp3?source=rss&amp=1"
ENC_C = "https://cdn.example.com/c.mp3"
URI_A = f"{GPODDER}://podcast_episode/{FEED} {GUID_A}"
URI_B = f"{GPODDER}://podcast_episode/{FEED} {GUID_B}"
URI_C = f"{GPODDER}://podcast_episode/{FEED} {ENC_C}"
KITCHEN = "media_player.kitchen"
NOW = 1792000000.0


def write(name: str, text: str) -> None:
    (OUT / name).write_text(json.dumps(json.loads(text), indent=2, sort_keys=True) + "\n")


def episode(item_id_tail: str, stream_url: str, name: str, position: int, duration: int):
    item_id = f"{FEED} {item_id_tail}"
    return PodcastEpisode(
        item_id=item_id,
        provider=GPODDER,
        name=name,
        position=position,
        duration=duration,
        podcast=ItemMapping(
            item_id=FEED, provider=GPODDER, name="Cotton Talk", media_type=MediaType.PODCAST
        ),
        provider_mappings={
            ProviderMapping(
                item_id=item_id,
                provider_domain="gpodder",
                provider_instance=GPODDER,
                audio_format=AudioFormat(content_type=ContentType.MP3),
                url=stream_url,
            )
        },
        fully_played=False,
        resume_position_ms=0,
    )


EP_A = episode(GUID_A, ENC_A, "Episode A", 3, 1800)
EP_B = episode(GUID_B, ENC_B, "Episode B", 2, 2400)
EP_C = episode(ENC_C, ENC_C, "Episode C has a guid with a space", 1, 0)


def queue_item(queue_item_id: str, ep: PodcastEpisode, index: int) -> QueueItem:
    return QueueItem(
        queue_id=KITCHEN,
        queue_item_id=queue_item_id,
        name=ep.name,
        duration=ep.duration or None,
        media_item=ep,
        index=index,
        sort_index=index,
    )


ITEM_A = queue_item("5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a", EP_A, 0)
ITEM_B = queue_item("0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f", EP_B, 1)
ITEM_C = queue_item("9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b", EP_C, 2)
# a plain URL enqueued as builtin://track/<url> (providers/builtin parse_item)
PLAIN_A = Track(
    item_id=ENC_A,
    provider="builtin",
    name="Episode A",
    duration=1800,
    provider_mappings={
        ProviderMapping(
            item_id=ENC_A,
            provider_domain="builtin",
            provider_instance="builtin",
            audio_format=AudioFormat(content_type=ContentType.MP3),
        )
    },
)
ITEM_PLAIN_A = QueueItem(queue_id=KITCHEN, queue_item_id="7f6e5d4c3b2a19087f6e5d4c3b2a1908",
                         name="Episode A", duration=1800, media_item=PLAIN_A)


def player(player_id: str, name: str, available: bool, features: set, ptype=PlayerType.PLAYER,
           state=PlaybackState.IDLE, volume=None, synced_to=None) -> Player:
    return Player(
        player_id=player_id,
        provider="sonos--a1b2c3d4" if ptype == PlayerType.PLAYER else "player_group",
        type=ptype,
        name=name,
        available=available,
        device_info=DeviceInfo(model="One SL", manufacturer="Sonos"),
        supported_features=features,
        playback_state=state,
        volume_level=volume,
        volume_muted=False if volume is not None else None,
        synced_to=synced_to,
        elapsed_time=None,
        elapsed_time_last_updated=None,
        active_source=player_id if state != PlaybackState.IDLE else None,
        current_media=PlayerMedia(uri=URI_A, media_type=MediaType.PODCAST_EPISODE,
                                  title="Episode A", queue_item_id=ITEM_A.queue_item_id,
                                  source_id=player_id) if state != PlaybackState.IDLE else None,
    )


KITCHEN_FEATURES = {PlayerFeature.VOLUME_SET, PlayerFeature.PAUSE, PlayerFeature.SEEK,
                    PlayerFeature.PLAY_MEDIA, PlayerFeature.ENQUEUE}

PLAYERS = [
    player(KITCHEN, "Kitchen", True, KITCHEN_FEATURES, volume=35),
    player("media_player.office", "Office", False, {PlayerFeature.PAUSE}),
    player("syncgroup_1", "Downstairs", True, {PlayerFeature.VOLUME_SET, PlayerFeature.PAUSE},
           ptype=PlayerType.GROUP, volume=40),
    player("media_player.bath", "Bathroom", True, {PlayerFeature.PAUSE}, synced_to=KITCHEN),
]


def queue(state=PlaybackState.PLAYING, current=ITEM_A, nxt=ITEM_B, index=0, elapsed=754.2,
          ended=False, items=2) -> PlayerQueue:
    q = PlayerQueue(
        queue_id=KITCHEN,
        active=True,
        display_name="Kitchen",
        available=True,
        items=items,
        current_index=index,
        index_in_buffer=index,
        elapsed_time=elapsed,
        elapsed_time_last_updated=NOW,
        playback_speed=1.0,
        state=state,
        current_item=current,
        next_item=nxt,
        ended=ended,
    )
    return q


# connection and auth
write("server_info.json", ServerInfoMessage(
    server_id="1f2e3d4c5b6a", server_version="2.11.0", schema_version=87,
    min_supported_schema_version=28, base_url="http://192.168.1.20:8095",
    onboard_done=True, name="Music Assistant").to_json())

USER = User(user_id="u-1f2e", username="alice", role="user",
            created_at=datetime(2026, 9, 1, tzinfo=UTC), display_name="Alice")

write("login_ok.json", SuccessResultMessage("1", {
    "success": True,
    "access_token": "short.lived.jwt",
    "user": {"user_id": USER.user_id, "username": USER.username,
             "display_name": USER.display_name, "role": USER.role},
}).to_json())
write("login_failed.json", SuccessResultMessage("1", {
    "success": False, "error": "Invalid username or password"}).to_json())
write("auth_ok.json", SuccessResultMessage("2", {
    "authenticated": True, "user": USER.to_dict()}).to_json())
write("auth_invalid_token.json", ErrorResultMessage(
    "2", 23, "Invalid or expired token").to_json())
write("token_created.json", SuccessResultMessage("3", "long.lived.jwt").to_json())
write("logout_ok.json", SuccessResultMessage("4", None).to_json())
write("time.json", SuccessResultMessage("5", NOW + 2.5).to_json())

write("providers.json", SuccessResultMessage("6", [
    {"type": "music", "domain": "gpodder", "name": "gPodder (Pod Pocket)",
     "instance_id": GPODDER, "supported_features": ["library_podcasts", "browse"],
     "available": True, "is_streaming_provider": False, "lookup_key": GPODDER},
    {"type": "music", "domain": "builtin", "name": "Music Assistant",
     "instance_id": "builtin", "supported_features": [], "available": True,
     "is_streaming_provider": False, "lookup_key": "builtin"},
    {"type": "player", "domain": "sonos", "name": "Sonos", "instance_id": "sonos--a1b2c3d4",
     "supported_features": [], "available": True, "is_streaming_provider": None,
     "lookup_key": "sonos--a1b2c3d4"},
]).to_json())

write("players_all.json", SuccessResultMessage("7", PLAYERS).to_json())
write("queues_all.json", SuccessResultMessage("8", [queue()]).to_json())
write("queues_all_second_item.json", SuccessResultMessage("8", [queue(
    current=ITEM_B, nxt=None, index=1, elapsed=30.0)]).to_json())
write("queues_all_ended.json", SuccessResultMessage("8", [queue(
    state=PlaybackState.IDLE, current=ITEM_B, nxt=None, index=1, elapsed=0.0,
    ended=True)]).to_json())
write("queues_all_idle.json", SuccessResultMessage("8", [queue(
    state=PlaybackState.IDLE, current=None, nxt=None, index=None, elapsed=0, items=0)]).to_json())
write("queue_get_idle.json", SuccessResultMessage("9", queue(
    state=PlaybackState.IDLE, current=None, nxt=None, index=None, elapsed=0, items=0)).to_json())
write("queue_items.json", SuccessResultMessage("10", [ITEM_A, ITEM_B]).to_json())
write("queue_items_plain_url.json", SuccessResultMessage("10", [ITEM_PLAIN_A]).to_json())
write("queue_items_current_only.json", SuccessResultMessage("10", [ITEM_A]).to_json())
write("queue_items_three.json", SuccessResultMessage("10", [ITEM_A, ITEM_B, ITEM_C]).to_json())
write("queue_items_play_next.json", SuccessResultMessage("10", [ITEM_A, ITEM_C, ITEM_B]).to_json())
write("command_ok.json", SuccessResultMessage("11", None).to_json())

# episode listing: the server streams async generators in chunks of 500 with partial=true
write("podcast_episodes_partial.json", SuccessResultMessage("12", [EP_A, EP_B], partial=True).to_json())
write("podcast_episodes_final.json", SuccessResultMessage("12", [EP_C]).to_json())

# errors
write("error_media_not_found.json", ErrorResultMessage(
    "13", 2, "Did not find episode.").to_json())
write("error_invalid_command.json", ErrorResultMessage(
    "14", 12, "Can not seek items without duration.").to_json())
write("error_player_unavailable.json", ErrorResultMessage(
    "15", 10, f"Queue {KITCHEN} is not available").to_json())

# events
write("event_queue_updated_playing.json", MassEvent(
    EventType.QUEUE_UPDATED, KITCHEN, queue()).to_json())
write("event_queue_updated_paused.json", MassEvent(
    EventType.QUEUE_UPDATED, KITCHEN, queue(state=PlaybackState.PAUSED, elapsed=800.0)).to_json())
write("event_queue_updated_next_item.json", MassEvent(
    EventType.QUEUE_UPDATED, KITCHEN,
    queue(current=ITEM_B, nxt=None, index=1, elapsed=0.0)).to_json())
write("event_queue_updated_ended.json", MassEvent(
    EventType.QUEUE_UPDATED, KITCHEN,
    queue(state=PlaybackState.IDLE, current=ITEM_A, nxt=None, index=0, elapsed=0.0,
          ended=True, items=1)).to_json())
write("event_queue_updated_ended_after_next.json", MassEvent(
    EventType.QUEUE_UPDATED, KITCHEN,
    queue(state=PlaybackState.IDLE, current=ITEM_B, nxt=None, index=1, elapsed=0.0,
          ended=True)).to_json())
write("event_queue_updated_third_item.json", MassEvent(
    EventType.QUEUE_UPDATED, KITCHEN,
    queue(current=ITEM_C, nxt=None, index=2, elapsed=12.0, items=3)).to_json())
write("event_queue_time_updated.json", MassEvent(
    EventType.QUEUE_TIME_UPDATED, KITCHEN, 900.5).to_json())
write("event_player_updated_unavailable.json", MassEvent(
    EventType.PLAYER_UPDATED, KITCHEN,
    player(KITCHEN, "Kitchen", False, KITCHEN_FEATURES, volume=35)).to_json())
write("event_queue_items_updated.json", MassEvent(
    EventType.QUEUE_ITEMS_UPDATED, KITCHEN, queue()).to_json())
write("event_unknown.json", MassEvent(EventType.MEDIA_ITEM_PLAYED, URI_A, {"x": 1}).to_json())
print(len(list(OUT.glob("*.json"))), "fixtures")
