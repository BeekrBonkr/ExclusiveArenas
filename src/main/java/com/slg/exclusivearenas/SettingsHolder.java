package com.slg.exclusivearenas;

/**
 * Something that carries a match's timeline/shop/team-size customizations — either a live
 * {@link PrivateSession} or an in-progress {@link DraftPrivateMatch} builder draft — so the
 * Event Timeline / Shop Items / Team Size editors can operate on either one.
 */
public interface SettingsHolder {

    SessionSettings getSettings();

    String getArenaName();
}
