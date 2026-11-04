using System;
using UnityEngine;

namespace CubeSnake.UI
{
    /// <summary>Pause overlay: resume, restart, settings (sound/controls/camera) and return to main menu.</summary>
    public sealed class PauseMenuController : MonoBehaviour
    {
        GameObject _root;
        GameObject _settings;
        Transform _canvas;
        Action _resume, _restart, _menu, _endRun;

        public bool IsOpen => _root != null && _root.activeSelf;

        public void Initialize(Transform canvas, Action resume, Action restart, Action menu, Action endRun)
        {
            _endRun = endRun;
            _canvas = canvas;
            _resume = resume;
            _restart = restart;
            _menu = menu;
        }

        public void Show()
        {
            if (_root == null) Build();
            _root.SetActive(true);
        }

        public void Hide()
        {
            if (_settings != null) Destroy(_settings);
            if (_root != null) _root.SetActive(false);
        }

        void Build()
        {
            var content = UIFactory.Modal(_canvas, "Paused", out _root, 820, 1260);
            UIFactory.VerticalLayout(content, 30, new RectOffset(0, 0, 20, 0));
            UIFactory.Button(content, "Resume", UITheme.Good, () => _resume?.Invoke());
            UIFactory.Button(content, "Restart", UITheme.Primary, () => _restart?.Invoke());
            UIFactory.Button(content, "Settings", UITheme.PanelLight, () =>
            {
                _root.SetActive(false);
                _settings = SettingsPanel.Open(_canvas, () => { _settings = null; if (_root != null) _root.SetActive(true); }, false);
            });
            UIFactory.Button(content, "End Run", UITheme.Accent, () => _endRun?.Invoke());
            UIFactory.Button(content, "Main Menu", UITheme.Danger, () => _menu?.Invoke());
        }

        /// <summary>Android back: close settings first, then resume.</summary>
        public void HandleBack()
        {
            if (_settings != null)
            {
                Destroy(_settings);
                _settings = null;
                _root.SetActive(true);
                return;
            }
            _resume?.Invoke();
        }
    }
}
