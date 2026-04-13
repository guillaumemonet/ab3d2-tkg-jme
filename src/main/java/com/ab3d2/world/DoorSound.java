package com.ab3d2.world;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gestion des sons de porte pour AB3D2.
 *
 * Les sons de porte viennent des fichiers WAV convertis depuis les samples Amiga
 * (convertis par SoundConverter depuis sounds/raw/ vers assets/Sounds/sfx/).
 *
 * Correspondance avec ZLiftableT (defs.i) :
 *   openSfx   = ZLiftableT_OpeningSoundFX_w  : son quand la porte commence a s'ouvrir
 *   closeSfx  = ZLiftableT_ClosingSoundFX_w  : son quand la porte commence a se fermer
 *   openedSfx = ZLiftableT_OpenedSoundFX_w   : son quand la porte est completement ouverte
 *   closedSfx = ZLiftableT_ClosedSoundFX_w   : son quand la porte est completement fermee
 *
 * Les sons sont 3D positionnels (attaches au centre de la porte).
 * Fallback : si l'index SFX ne correspond pas a un fichier connu, on utilise
 * les sons generiques de porte (newdoor.wav, door.wav).
 */
public class DoorSound {

    private static final Logger log = LoggerFactory.getLogger(DoorSound.class);

    // Sons par defaut si les indices SFX ne correspondent pas
    private static final String[] FALLBACK_DOOR_SOUNDS = {
        "Sounds/sfx/newdoor.wav",
        "Sounds/sfx/door.wav",
        "Sounds/sfx/dooropen.wav",
        "Sounds/sfx/door01.wav",
    };

    // Volume et portee
    private static final float DOOR_VOLUME      = 0.8f;
    private static final float DOOR_MAX_DIST    = 20f;
    private static final float DOOR_REF_DIST    = 3f;

    private final AudioNode openSound;
    private final AudioNode closeSound;

    /**
     * Cree les AudioNodes pour une porte.
     *
     * @param am         AssetManager JME
     * @param parent     Node de la porte (pour attacher les AudioNodes)
     * @param center     Position 3D de la porte
     * @param openSfxIdx  Index SFX ouverture (depuis ZLiftable, peut etre 0 = pas de son)
     * @param closeSfxIdx Index SFX fermeture
     */
    public DoorSound(AssetManager am, Node parent, Vector3f center,
                     int openSfxIdx, int closeSfxIdx) {
        openSound  = loadDoorSound(am, openSfxIdx,  parent, center, "door_open");
        closeSound = loadDoorSound(am, closeSfxIdx, parent, center, "door_close");
    }

    private AudioNode loadDoorSound(AssetManager am, int sfxIdx, Node parent,
                                    Vector3f pos, String nodeName) {
        // Essayer en priorite les fichiers par defaut (les indices SFX de AB3D2
        // referencent la base de donnees GLF que nous n'avons pas completement,
        // donc on utilise directement les fichiers raw connus)
        String[] candidates = FALLBACK_DOOR_SOUNDS;

        for (String path : candidates) {
            try {
                AudioNode an = new AudioNode(am, path, AudioData.DataType.Buffer);
                an.setName(nodeName);
                an.setPositional(true);
                an.setLocalTranslation(pos);
                an.setVolume(DOOR_VOLUME);
                an.setMaxDistance(DOOR_MAX_DIST);
                an.setRefDistance(DOOR_REF_DIST);
                an.setLooping(false);
                parent.attachChild(an);
                return an;
            } catch (Exception e) {
                // Fichier non trouve, essayer le suivant
            }
        }

        log.warn("Aucun son de porte trouve (sfxIdx={}), sons de porte muets", sfxIdx);
        return null;
    }

    /** Joue le son d'ouverture. */
    public void playOpen() {
        if (openSound != null) openSound.playInstance();
    }

    /** Joue le son de fermeture. */
    public void playClose() {
        if (closeSound != null) closeSound.playInstance();
    }

    /** Met a jour la position 3D des sons (si la porte est mobile). */
    public void setPosition(Vector3f pos) {
        if (openSound  != null) openSound.setLocalTranslation(pos);
        if (closeSound != null) closeSound.setLocalTranslation(pos);
    }
}
