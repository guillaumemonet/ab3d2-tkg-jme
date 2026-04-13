package com.ab3d2.world;

import com.jme3.asset.AssetManager;
import com.jme3.audio.Listener;

/**
 * Reference statique a l'AssetManager et au Listener audio JME.
 *
 * Permet aux AbstractControl (DoorControl etc.) d'acceder a l'AssetManager
 * sans avoir a le passer en parametre a chaque controlUpdate().
 *
 * Initialise par GameAppState.initialize() et remis a null dans cleanup().
 */
public class GameSoundRef {
    public static AssetManager assetManager = null;
    public static Listener     audioListener = null;
}
