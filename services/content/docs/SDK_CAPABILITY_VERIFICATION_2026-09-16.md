# Short-drama SDK capability verification

**Verified on:** 2026-09-16

## Search capability

The exact Android dependency currently packaged by the app, `com.bytedance.dramaverse:pssdk:1.8.0.0`, exposes the following public method when inspected from its AAR:

```java
PSSDK.searchDrama(String keyword, boolean, int page, int pageSize, PSSDK.FeedListResultListener)
```

The same public interface exposes `requestPopularDrama`, `requestNewDrama`, `requestCategoryList`, `requestDramaByTag`, and `requestFeedListByCategoryIds`. Accordingly, a production build with `SHORT_DRAMA_SDK_ENABLED=true` can use PSSDK as the content source for search and the Discover page.

The current test build intentionally has `SHORT_DRAMA_SDK_ENABLED=false`, so catalog and playback remain on the local owned-content API. Search must therefore filter the downloaded local catalog during testing; the same screen can call `searchDrama` after the external SDK is enabled and its required credentials/license are supplied.

## External reference

The official BytePlus VideoOne Android integration guide describes the short-drama demonstration solution as supporting episode switching, seamless playback resumption when changing pages, paid-content unlocks, and screen-capture protection. It lists theater/home, “For you” recommendation, and content data-layer integration but does not document a separate end-user search screen in that guide. The concrete `searchDrama` method above was verified directly from the exact AAR shipped in this repository.

Source: [BytePlus VideoOne — Short drama videos: Android integration](https://docs.byteplus.com/zh-CN/docs/byteplus-vos/short-drama-videos-android-integration)
