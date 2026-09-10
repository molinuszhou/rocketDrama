from __future__ import annotations

from datetime import UTC, datetime, timedelta
from urllib.parse import quote

from fastapi import HTTPException, status

from app.local_media import LocalPlaybackSessionStore
from app.models import (
    ContentSource,
    DramaCard,
    DramaDetail,
    EntitlementState,
    Episode,
    FeedPage,
    PlaybackMode,
    PlaybackSessionResponse,
    ProviderHealth,
    UnlockMethod,
    UnlockResponse,
)
from app.providers import ContentProvider, FreeDramaProvider, PangleDramaProvider


class ContentOrchestrator:
    """内容融合与业务约束层。

    提供方只管理自己来源的数据。地区判断、跨来源排序、播放会话和统一错误语义在此层完成。
    """

    def __init__(
        self,
        free_drama: FreeDramaProvider,
        pangle_drama: PangleDramaProvider,
        local_playback_sessions: LocalPlaybackSessionStore | None = None,
    ) -> None:
        self._providers: dict[ContentSource, ContentProvider] = {
            ContentSource.FREE_DRAMA: free_drama,
            ContentSource.PANGLE_DRAMA: pangle_drama,
        }
        self._free_drama = free_drama
        self._local_playback_sessions = local_playback_sessions

    def feed(self, region: str, language: str | None, cursor: str | None, limit: int) -> FeedPage:
        all_items: list[DramaCard] = []
        for provider in self._providers.values():
            all_items.extend(provider.list_dramas(region=region, language=language))

        # 稳定的来源交错排序：自由剧优先保障自有内容，平台内容可在其正式适配器启用后按运营策略插入。
        all_items.sort(key=lambda item: (item.source != ContentSource.FREE_DRAMA, item.title))
        offset = self._decode_cursor(cursor)
        page_items = all_items[offset : offset + limit]
        next_cursor = str(offset + limit) if offset + limit < len(all_items) else None
        return FeedPage(items=page_items, next_cursor=next_cursor)

    def drama_detail(self, drama_id: str, region: str, user_id: str | None) -> DramaDetail:
        provider = self._provider_for_id(drama_id)
        drama = provider.get_drama(drama_id, region, user_id)
        if drama is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="剧集不存在、未授权或当前地区不可用")

        episodes = []
        for index in range(1, drama.total_episodes + 1):
            episode = provider.get_episode(self._episode_id(drama_id, index), region, user_id)
            if episode:
                episodes.append(episode)
        return DramaDetail(**drama.model_dump(), episodes=episodes)

    def playback_session(
        self,
        episode_id: str,
        region: str,
        user_id: str | None,
        device_id: str | None,
        request_base_url: str | None = None,
    ) -> PlaybackSessionResponse:
        episode = self._get_playable_episode(episode_id=episode_id, region=region, user_id=user_id)

        if episode.playback_mode == PlaybackMode.PANGLE_SDK:
            return PlaybackSessionResponse(
                episode_id=episode.id,
                source=ContentSource.PANGLE_DRAMA,
                playback_mode=PlaybackMode.PANGLE_SDK,
                entitlement=episode.entitlement,
                sdk_route="pangle_content_sdk",
                sdk_payload={"episode_id": episode.id, "region": region},
                message="请由 Android Pangle 内容 SDK 处理播放和平台权益。",
            )

        if episode.media_provider == "local_filesystem":
            return self._local_filesystem_playback_session(episode, request_base_url)

        expires_at = datetime.now(UTC) + timedelta(minutes=10)
        # 原型签名 URL。生产环境替换为 CDN、VOD 或 DRM 服务生成的短期签名 URL，且不得在客户端写入媒体源密钥。
        token = quote(f"demo-{episode.id}-{expires_at.timestamp():.0f}")
        return PlaybackSessionResponse(
            episode_id=episode.id,
            source=ContentSource.FREE_DRAMA,
            playback_mode=PlaybackMode.SIGNED_URL,
            entitlement=episode.entitlement,
            expires_at=expires_at,
            stream_url=f"https://stream.example.invalid/v1/free-drama/{quote(episode.id, safe='')}/master.m3u8?token={token}",
            message="原型播放会话已签发；该 URL 为不可播放的示例地址，正式环境请接入受控 CDN/VOD。",
        )

    def unlock_episode(self, episode_id: str, region: str, user_id: str, method: UnlockMethod, proof: str | None) -> UnlockResponse:
        provider = self._provider_for_id(episode_id)
        episode = provider.get_episode(episode_id, region, user_id)
        if episode is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="分集不存在、未授权或当前地区不可用")
        if provider.source != ContentSource.FREE_DRAMA:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Pangle 内容必须通过合作方 SDK 与服务端回调确认权益，不能由本服务直接解锁")
        if episode.entitlement == EntitlementState.FREE:
            return UnlockResponse(episode_id=episode_id, unlocked=True, entitlement=EntitlementState.FREE, message="该分集已经可播放")
        expected_debug_proof = {
            UnlockMethod.AD_INTERSTITIAL: "TEST_INTERSTITIAL_PROOF",
            UnlockMethod.AD_REWARD: "TEST_REWARD_PROOF",
        }.get(method)
        if expected_debug_proof and not proof:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="广告解锁需要服务端验证的广告证明")
        if expected_debug_proof and proof != expected_debug_proof:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="广告证明无效；生产环境应由广告服务端回调完成验签")

        self._free_drama.mark_unlocked(user_id, episode_id)
        return UnlockResponse(
            episode_id=episode_id,
            unlocked=True,
            entitlement=EntitlementState.FREE,
            message="自有剧测试权益已记录；生产环境请替换为事务化权益账本与服务端广告展示验签。",
        )

    def provider_health(self) -> list[ProviderHealth]:
        return [provider.health() for provider in self._providers.values()]

    def _get_playable_episode(self, episode_id: str, region: str, user_id: str | None) -> Episode:
        provider = self._provider_for_id(episode_id)
        episode = provider.get_episode(episode_id, region, user_id)
        if episode is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="分集不存在、未授权或当前地区不可用")
        if episode.entitlement == EntitlementState.UNAVAILABLE:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="该分集当前不可播放")
        if episode.entitlement in {EntitlementState.AD_UNLOCK, EntitlementState.COIN_UNLOCK}:
            raise HTTPException(status_code=status.HTTP_402_PAYMENT_REQUIRED, detail="需要先完成该分集的解锁条件")
        return episode

    def _local_filesystem_playback_session(self, episode: Episode, request_base_url: str | None) -> PlaybackSessionResponse:
        if self._local_playback_sessions is None or not request_base_url:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="本地媒体测试模式未启用；请设置 ENVIRONMENT=development、LOCAL_MEDIA_ENABLED=true 和 LOCAL_MEDIA_ROOT",
            )
        if not episode.media_asset_id:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="本地测试分集缺少 videoAssetId 相对路径")

        token, expires_at = self._local_playback_sessions.issue(
            episode_id=episode.id,
            relative_path=episode.media_asset_id,
        )
        stream_url = f"{request_base_url.rstrip('/')}/v1/dev/local-media/{quote(episode.id, safe='')}?token={quote(token, safe='')}"
        return PlaybackSessionResponse(
            episode_id=episode.id,
            source=ContentSource.FREE_DRAMA,
            playback_mode=PlaybackMode.SIGNED_URL,
            entitlement=episode.entitlement,
            expires_at=expires_at,
            stream_url=stream_url,
            message="本地文件系统测试播放会话已签发，仅可在开发环境使用。",
        )

    def _provider_for_id(self, entity_id: str) -> ContentProvider:
        if entity_id.startswith(("free:", "owned:")):
            return self._providers[ContentSource.FREE_DRAMA]
        if entity_id.startswith("pangle:"):
            return self._providers[ContentSource.PANGLE_DRAMA]
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="不支持的内容来源 ID")

    @staticmethod
    def _episode_id(drama_id: str, index: int) -> str:
        return f"{drama_id}:ep:{index}"

    @staticmethod
    def _decode_cursor(cursor: str | None) -> int:
        if cursor is None:
            return 0
        try:
            offset = int(cursor)
        except ValueError as error:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="cursor 必须为非负整数") from error
        if offset < 0:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="cursor 必须为非负整数")
        return offset
