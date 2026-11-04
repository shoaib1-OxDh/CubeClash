using UnityEngine;

namespace CubeSnake.Audio
{
    /// <summary>
    /// Synthesises every sound effect and the background music at startup. All audio is original (generated from
    /// simple oscillators and noise), so there are no third-party licensing concerns.
    /// </summary>
    public static class ProceduralAudio
    {
        public const int SampleRate = 22050;

        static float Env(float t, float attack, float decay)
        {
            if (t < attack) return t / attack;
            return Mathf.Exp(-(t - attack) / decay);
        }

        static AudioClip Make(string name, float seconds, System.Func<float, float> fn)
        {
            int n = Mathf.CeilToInt(seconds * SampleRate);
            var data = new float[n];
            for (int i = 0; i < n; i++) data[i] = Mathf.Clamp(fn(i / (float)SampleRate), -1f, 1f);
            // Short fade-out to avoid clicks.
            int fade = Mathf.Min(200, n);
            for (int i = 0; i < fade; i++) data[n - 1 - i] *= i / (float)fade;
            var clip = AudioClip.Create(name, n, 1, SampleRate, false);
            clip.SetData(data, 0);
            return clip;
        }

        static float Sine(float f, float t) => Mathf.Sin(2f * Mathf.PI * f * t);
        static float Tri(float f, float t) => 2f * Mathf.Abs(2f * (t * f - Mathf.Floor(t * f + 0.5f))) - 1f;
        static float Square(float f, float t, float duty = 0.5f) => (t * f) % 1f < duty ? 1f : -1f;

        public static AudioClip Collect() => Make("sfx_collect", 0.14f, t =>
        {
            float f = Mathf.Lerp(880f, 1500f, t / 0.14f);
            return (Sine(f, t) * 0.6f + Tri(f * 2f, t) * 0.15f) * Env(t, 0.004f, 0.05f) * 0.7f;
        });

        /// <summary>Merge chime; <paramref name="tier"/> 0..4 adds brightness and a sparkle for high values.</summary>
        public static AudioClip Merge(int tier) => Make("sfx_merge_" + tier, 0.32f + tier * 0.06f, t =>
        {
            float baseF = 523.25f * Mathf.Pow(2f, tier * 3 / 12f);
            float s = Sine(baseF, t) * 0.5f + Sine(baseF * 1.5f, t) * 0.25f * Mathf.Clamp01(t / 0.05f);
            s += Sine(baseF * 2f, t) * 0.18f * tier / 4f;
            if (tier >= 2) s += Sine(baseF * 3.01f, t) * 0.12f * Env(t, 0.001f, 0.06f);
            if (tier >= 3) s += Sine(baseF * 4f + 7f * Mathf.Sin(t * 40f), t) * 0.1f * Env(Mathf.Max(0, t - 0.06f), 0.01f, 0.12f);
            return s * Env(t, 0.005f, 0.12f + tier * 0.03f) * 0.65f;
        });

        public static AudioClip Boost()
        {
            var rng = new System.Random(3);
            float lp = 0f;
            return Make("sfx_boost", 0.4f, t =>
            {
                float noise = (float)rng.NextDouble() * 2f - 1f;
                float cutoff = Mathf.Lerp(0.05f, 0.35f, t / 0.4f);
                lp += (noise - lp) * cutoff;
                return (lp * 0.9f + Sine(Mathf.Lerp(180f, 420f, t / 0.4f), t) * 0.25f) * Env(t, 0.03f, 0.15f) * 0.6f;
            });
        }

        public static AudioClip OpponentDefeat()
        {
            var rng = new System.Random(7);
            return Make("sfx_defeat_rival", 0.5f, t =>
            {
                float noise = (float)rng.NextDouble() * 2f - 1f;
                float f = Mathf.Lerp(600f, 140f, t / 0.5f);
                return (Square(f, t, 0.3f) * 0.25f + noise * 0.35f * Env(t, 0.001f, 0.06f) + Sine(f * 0.5f, t) * 0.3f) * Env(t, 0.003f, 0.16f) * 0.7f;
            });
        }

        public static AudioClip PlayerDefeat() => Make("sfx_defeat_player", 0.9f, t =>
        {
            float f = Mathf.Lerp(420f, 70f, Mathf.Sqrt(t / 0.9f));
            return (Tri(f, t) * 0.5f + Sine(f * 0.5f, t) * 0.4f) * Env(t, 0.01f, 0.35f) * 0.75f;
        });

        public static AudioClip Click() => Make("sfx_click", 0.06f, t => Sine(1250f, t) * Env(t, 0.001f, 0.015f) * 0.6f);

        public static AudioClip HighScore()
        {
            float[] notes = { 523.25f, 659.25f, 783.99f, 1046.5f };
            return Make("sfx_highscore", 1.1f, t =>
            {
                float s = 0f;
                for (int i = 0; i < notes.Length; i++)
                {
                    float start = i * 0.12f;
                    if (t < start) continue;
                    float lt = t - start;
                    s += (Sine(notes[i], lt) * 0.5f + Sine(notes[i] * 2f, lt) * 0.15f) * Env(lt, 0.005f, i == notes.Length - 1 ? 0.45f : 0.12f);
                }
                return s * 0.45f;
            });
        }

        public static AudioClip Warning() => Make("sfx_warning", 0.22f, t => Square(t < 0.11f ? 660f : 520f, t, 0.5f) * 0.18f * Env(t, 0.005f, 0.08f));

        /// <summary>Calm looping background track (16 bars of a I-vi-IV-V progression with arpeggio and soft pad).</summary>
        public static AudioClip Music()
        {
            const float bpm = 104f;
            float beat = 60f / bpm;
            int bars = 8;
            float seconds = bars * 4 * beat;
            // Chord roots (Hz) for C, Am, F, G.
            float[][] chords =
            {
                new[] { 261.63f, 329.63f, 392.00f }, new[] { 220.00f, 261.63f, 329.63f },
                new[] { 174.61f, 220.00f, 261.63f }, new[] { 196.00f, 246.94f, 293.66f },
            };
            return Make("music_loop", seconds, t =>
            {
                int bar = (int)(t / (4 * beat)) % chords.Length;
                var chord = chords[bar];
                float barT = t % (4 * beat);
                // Pad.
                float pad = 0f;
                for (int i = 0; i < 3; i++) pad += Sine(chord[i], t) * 0.06f + Sine(chord[i] * 1.003f, t) * 0.04f;
                pad *= 0.8f + 0.2f * Mathf.Sin(t * 0.7f);
                // Arpeggio (eighth notes).
                float eighth = beat * 0.5f;
                int step = (int)(barT / eighth);
                float noteT = barT % eighth;
                float noteF = chord[step % 3] * (step % 6 >= 3 ? 2f : 1f) * 2f;
                float arp = (Tri(noteF, t) * 0.5f + Sine(noteF, t) * 0.5f) * Env(noteT, 0.005f, 0.09f) * 0.13f;
                // Bass on beats.
                float beatT = barT % beat;
                float bass = Sine(chord[0] * 0.5f, t) * Env(beatT, 0.01f, 0.18f) * 0.22f;
                // Soft hat on off-beats.
                float hatT = (barT + beat * 0.5f) % beat;
                float hat = (Mathf.PerlinNoise(t * 9000f, 0.5f) * 2f - 1f) * Env(hatT, 0.001f, 0.015f) * 0.05f;
                return (pad + arp + bass + hat) * 0.9f;
            });
        }
    }
}
