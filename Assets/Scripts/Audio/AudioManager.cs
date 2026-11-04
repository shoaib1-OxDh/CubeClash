using System.Collections;
using CubeSnake.Chain;
using CubeSnake.Core;
using UnityEngine;

namespace CubeSnake.Audio
{
    public enum Sfx { Collect, Boost, OpponentDefeat, PlayerDefeat, Click, HighScore, Warning }

    /// <summary>
    /// Plays music and pooled one-shot sound effects with separate music / SFX volumes and mute.
    /// Merge sounds become more distinctive as values grow (higher tiers and pitch).
    /// </summary>
    public sealed class AudioManager : MonoBehaviour
    {
        const int VoiceCount = 10;

        AudioSource _music;
        AudioSource[] _voices;
        int _nextVoice;
        AudioClip _collect, _boost, _defeatRival, _defeatPlayer, _click, _highScore, _warning;
        AudioClip[] _merge;
        float _lastCollectTime;
        bool _ready;

        void Awake()
        {
            _music = gameObject.AddComponent<AudioSource>();
            _music.loop = true;
            _music.playOnAwake = false;
            _music.spatialBlend = 0f;
            _voices = new AudioSource[VoiceCount];
            for (int i = 0; i < VoiceCount; i++)
            {
                var v = gameObject.AddComponent<AudioSource>();
                v.playOnAwake = false;
                v.spatialBlend = 0f;
                _voices[i] = v;
            }
            SettingsManager.Changed += ApplyVolumes;
            StartCoroutine(Generate());
        }

        void OnDestroy() => SettingsManager.Changed -= ApplyVolumes;

        IEnumerator Generate()
        {
            // Spread synthesis over a few frames to keep startup smooth.
            _click = ProceduralAudio.Click();
            _collect = ProceduralAudio.Collect();
            yield return null;
            _merge = new AudioClip[5];
            for (int i = 0; i < _merge.Length; i++) _merge[i] = ProceduralAudio.Merge(i);
            yield return null;
            _boost = ProceduralAudio.Boost();
            _defeatRival = ProceduralAudio.OpponentDefeat();
            _defeatPlayer = ProceduralAudio.PlayerDefeat();
            _highScore = ProceduralAudio.HighScore();
            _warning = ProceduralAudio.Warning();
            _ready = true;
            yield return null;
            _music.clip = ProceduralAudio.Music();
            ApplyVolumes();
            _music.Play();
        }

        void OnEnable()
        {
            GameEvents.CubeCollected += OnCollected;
            GameEvents.CubesMerged += OnMerged;
            GameEvents.BoostStarted += OnBoost;
        }

        void OnDisable()
        {
            GameEvents.CubeCollected -= OnCollected;
            GameEvents.CubesMerged -= OnMerged;
            GameEvents.BoostStarted -= OnBoost;
        }

        public void ApplyVolumes()
        {
            if (_music == null) return;
            bool muted = SettingsManager.Muted;
            _music.volume = muted ? 0f : SettingsManager.MusicVolume * 0.6f;
            _music.mute = muted;
        }

        public void Play(Sfx sfx, float pitch = 1f, float volume = 1f)
        {
            if (!_ready) return;
            AudioClip clip;
            switch (sfx)
            {
                case Sfx.Collect: clip = _collect; break;
                case Sfx.Boost: clip = _boost; break;
                case Sfx.OpponentDefeat: clip = _defeatRival; break;
                case Sfx.PlayerDefeat: clip = _defeatPlayer; break;
                case Sfx.HighScore: clip = _highScore; break;
                case Sfx.Warning: clip = _warning; break;
                default: clip = _click; break;
            }
            PlayClip(clip, pitch, volume);
        }

        void PlayClip(AudioClip clip, float pitch, float volume)
        {
            if (clip == null || SettingsManager.Muted) return;
            float v = SettingsManager.SfxVolume * volume;
            if (v <= 0.001f) return;
            var voice = _voices[_nextVoice];
            _nextVoice = (_nextVoice + 1) % _voices.Length;
            voice.pitch = pitch;
            voice.volume = v;
            voice.clip = clip;
            voice.Play();
        }

        public void PlayMerge(int level)
        {
            if (!_ready) return;
            int tier = Mathf.Clamp((level - 1) / 3, 0, _merge.Length - 1);
            float pitch = 1f + ((level - 1) % 3) * 0.06f;
            PlayClip(_merge[tier], pitch, 0.9f);
        }

        void OnCollected(CubeChainController chain, int level, Vector3 pos)
        {
            if (!chain.IsPlayer) return;
            // Rapid collections rise in pitch (combo feel) and never stack too loudly.
            float since = Time.unscaledTime - _lastCollectTime;
            _lastCollectTime = Time.unscaledTime;
            float pitch = since < 0.4f ? 1.12f : 1f;
            Play(Sfx.Collect, pitch + level * 0.015f, 0.8f);
        }

        void OnMerged(CubeChainController chain, int level, Vector3 pos)
        {
            if (chain.IsPlayer) PlayMerge(level);
        }

        void OnBoost(CubeChainController chain)
        {
            if (chain.IsPlayer) Play(Sfx.Boost, 1f, 0.7f);
        }
    }
}
