package com.slg.exclusivearenas;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DraftService {

    private final Map<UUID, DraftPrivateMatch> drafts = new ConcurrentHashMap<>();

    public DraftPrivateMatch getOrCreate(UUID owner) {
        return drafts.computeIfAbsent(owner, DraftPrivateMatch::new);
    }

    public DraftPrivateMatch get(UUID owner) {
        return drafts.get(owner);
    }

    public void store(UUID owner, DraftPrivateMatch draft) {
        drafts.put(owner, draft);
    }

    public void clear(UUID owner) {
        drafts.remove(owner);
    }
}
