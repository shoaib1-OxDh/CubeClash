using System;
using System.Collections.Generic;
using CubeSnake.Audio;
using CubeSnake.Core;
using CubeSnake.Visual;
using UnityEngine;
using UnityEngine.Events;
using UnityEngine.UI;

namespace CubeSnake.UI
{
    /// <summary>Theme colours for the procedural UI.</summary>
    public static class UITheme
    {
        public static readonly Color Primary = new Color(0.31f, 0.49f, 1f);
        public static readonly Color Accent = new Color(1f, 0.69f, 0.13f);
        public static readonly Color Good = new Color(0.22f, 0.78f, 0.45f);
        public static readonly Color Danger = new Color(1f, 0.3f, 0.37f);
        public static readonly Color Panel = new Color(0.08f, 0.1f, 0.2f, 0.88f);
        public static readonly Color PanelLight = new Color(1f, 1f, 1f, 0.12f);
        public static readonly Color Text = Color.white;
        public static readonly Color TextDim = new Color(1f, 1f, 1f, 0.7f);
        public static readonly Color Overlay = new Color(0.03f, 0.04f, 0.1f, 0.6f);
    }

    /// <summary>
    /// Builds uGUI hierarchies from code (canvas, panels, labels, buttons, sliders, segmented selectors) so that no
    /// prefab wiring is required and every screen scales with CanvasScaler for any Android resolution/aspect.
    /// </summary>
    public static class UIFactory
    {
        static Font s_font;

        public static Font Font
        {
            get
            {
                if (s_font != null) return s_font;
                // Unity 2022.2+ ships LegacyRuntime.ttf; older versions ship Arial.ttf.
                try { s_font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf"); } catch { s_font = null; }
                if (s_font == null) { try { s_font = Resources.GetBuiltinResource<Font>("Arial.ttf"); } catch { s_font = null; } }
                if (s_font == null) s_font = Font.CreateDynamicFontFromOSFont("Arial", 32);
                return s_font;
            }
        }

        public static Canvas CreateCanvas(string name, int sortOrder, Transform parent = null)
        {
            var go = new GameObject(name, typeof(RectTransform));
            if (parent != null) go.transform.SetParent(parent, false);
            var canvas = go.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = sortOrder;
            canvas.pixelPerfect = false;
            var scaler = go.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1080, 1920);
            scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
            scaler.matchWidthOrHeight = 0.5f;
            go.AddComponent<GraphicRaycaster>();
            go.AddComponent<CanvasMatchAdapter>();
            return canvas;
        }

        public static RectTransform Rect(string name, Transform parent)
        {
            var go = new GameObject(name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            return (RectTransform)go.transform;
        }

        public static RectTransform Stretch(RectTransform rt, float left = 0, float right = 0, float top = 0, float bottom = 0)
        {
            rt.anchorMin = Vector2.zero;
            rt.anchorMax = Vector2.one;
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.offsetMin = new Vector2(left, bottom);
            rt.offsetMax = new Vector2(-right, -top);
            return rt;
        }

        /// <summary>Anchors at a single point (anchor = pivot) with a size and offset.</summary>
        public static RectTransform Place(RectTransform rt, Vector2 anchor, Vector2 size, Vector2 offset)
        {
            rt.anchorMin = rt.anchorMax = anchor;
            rt.pivot = anchor;
            rt.sizeDelta = size;
            rt.anchoredPosition = offset;
            return rt;
        }

        public static RectTransform SafeArea(Transform canvas)
        {
            var rt = Stretch(Rect("SafeArea", canvas));
            rt.gameObject.AddComponent<SafeAreaFitter>();
            return rt;
        }

        public static Image Panel(Transform parent, Color color, int radius = 28, string name = "Panel")
        {
            var rt = Rect(name, parent);
            var img = rt.gameObject.AddComponent<Image>();
            img.sprite = radius > 0 ? TextureFactory.RoundedRect(radius) : null;
            img.type = radius > 0 ? Image.Type.Sliced : Image.Type.Simple;
            img.color = color;
            return img;
        }

        public static Text Label(Transform parent, string text, int size, Color color, TextAnchor align = TextAnchor.MiddleCenter, bool bold = true, bool shadow = true)
        {
            var rt = Rect("Label", parent);
            var t = rt.gameObject.AddComponent<Text>();
            t.font = Font;
            t.text = text;
            t.fontSize = size;
            t.color = color;
            t.alignment = align;
            t.fontStyle = bold ? FontStyle.Bold : FontStyle.Normal;
            t.horizontalOverflow = HorizontalWrapMode.Wrap;
            t.verticalOverflow = VerticalWrapMode.Overflow;
            t.raycastTarget = false;
            t.supportRichText = true;
            if (shadow)
            {
                var s = rt.gameObject.AddComponent<Shadow>();
                s.effectColor = new Color(0f, 0f, 0.1f, 0.45f);
                s.effectDistance = new Vector2(2f, -3f);
            }
            return t;
        }

        public static Button Button(Transform parent, string label, Color color, UnityAction onClick, int fontSize = 52)
        {
            var img = Panel(parent, color, 30, "Button_" + label);
            var btn = img.gameObject.AddComponent<Button>();
            var colors = btn.colors;
            colors.normalColor = Color.white;
            colors.highlightedColor = new Color(1f, 1f, 1f, 0.95f);
            colors.pressedColor = new Color(0.8f, 0.8f, 0.85f, 1f);
            colors.disabledColor = new Color(0.6f, 0.6f, 0.6f, 0.6f);
            colors.fadeDuration = 0.06f;
            btn.colors = colors;
            btn.targetGraphic = img;
            btn.onClick.AddListener(() => Services.Audio?.Play(Sfx.Click));
            if (onClick != null) btn.onClick.AddListener(onClick);
            var text = Label(img.transform, label, fontSize, Color.white);
            Stretch(text.rectTransform, 16, 16, 4, 4);
            img.gameObject.AddComponent<ButtonPressScale>();
            var le = img.gameObject.AddComponent<LayoutElement>();
            le.minHeight = 120;
            le.preferredHeight = 130;
            return btn;
        }

        public static Text ButtonLabel(Button b) => b.GetComponentInChildren<Text>();

        public static VerticalLayoutGroup VerticalLayout(RectTransform rt, float spacing, RectOffset padding = null, TextAnchor align = TextAnchor.UpperCenter)
        {
            var v = rt.gameObject.AddComponent<VerticalLayoutGroup>();
            v.spacing = spacing;
            v.padding = padding ?? new RectOffset(0, 0, 0, 0);
            v.childAlignment = align;
            v.childControlWidth = true;
            v.childControlHeight = true;
            v.childForceExpandWidth = true;
            v.childForceExpandHeight = false;
            return v;
        }

        public static HorizontalLayoutGroup HorizontalLayout(RectTransform rt, float spacing)
        {
            var h = rt.gameObject.AddComponent<HorizontalLayoutGroup>();
            h.spacing = spacing;
            h.childAlignment = TextAnchor.MiddleCenter;
            h.childControlWidth = true;
            h.childControlHeight = true;
            h.childForceExpandWidth = true;
            h.childForceExpandHeight = true;
            return h;
        }

        public static LayoutElement Layout(Component c, float preferredHeight, float minHeight = -1)
        {
            var le = c.GetComponent<LayoutElement>() ?? c.gameObject.AddComponent<LayoutElement>();
            le.preferredHeight = preferredHeight;
            le.minHeight = minHeight >= 0 ? minHeight : preferredHeight;
            return le;
        }

        /// <summary>A labelled horizontal slider row.</summary>
        public static Slider SliderRow(Transform parent, string label, float min, float max, float value, Action<float> onChange)
        {
            var row = Rect("Row_" + label, parent);
            Layout(row, 110);
            var text = Label(row, label, 40, UITheme.Text, TextAnchor.MiddleLeft);
            var trt = text.rectTransform;
            trt.anchorMin = new Vector2(0, 0); trt.anchorMax = new Vector2(0.42f, 1); trt.offsetMin = trt.offsetMax = Vector2.zero;

            var sliderRt = Rect("Slider", row);
            sliderRt.anchorMin = new Vector2(0.45f, 0.2f); sliderRt.anchorMax = new Vector2(1f, 0.8f);
            sliderRt.offsetMin = sliderRt.offsetMax = Vector2.zero;
            var bg = sliderRt.gameObject.AddComponent<Image>();
            bg.sprite = TextureFactory.RoundedRect(20);
            bg.type = Image.Type.Sliced;
            bg.color = new Color(1f, 1f, 1f, 0.15f);

            var fillArea = Rect("FillArea", sliderRt);
            Stretch(fillArea, 10, 10, 10, 10);
            var fill = Panel(fillArea, UITheme.Primary, 16, "Fill");
            fill.rectTransform.anchorMin = Vector2.zero; fill.rectTransform.anchorMax = new Vector2(0, 1);
            fill.rectTransform.offsetMin = fill.rectTransform.offsetMax = Vector2.zero;

            var handleArea = Rect("HandleArea", sliderRt);
            Stretch(handleArea, 24, 24, 0, 0);
            var handle = Rect("Handle", handleArea);
            handle.sizeDelta = new Vector2(64, 0);
            handle.anchorMin = new Vector2(0, 0); handle.anchorMax = new Vector2(0, 1);
            var hImg = handle.gameObject.AddComponent<Image>();
            hImg.sprite = TextureFactory.CircleSprite();
            hImg.color = Color.white;
            hImg.preserveAspect = true;

            var slider = sliderRt.gameObject.AddComponent<Slider>();
            slider.fillRect = fill.rectTransform;
            slider.handleRect = handle;
            slider.targetGraphic = hImg;
            slider.direction = UnityEngine.UI.Slider.Direction.LeftToRight;
            slider.minValue = min;
            slider.maxValue = max;
            slider.value = value;
            slider.onValueChanged.AddListener(v => onChange?.Invoke(v));
            return slider;
        }

        /// <summary>Segmented selector: one button per option, the selected one highlighted.</summary>
        public static List<Button> Segmented(Transform parent, string label, string[] options, int selected, Action<int> onSelect, float height = 110)
        {
            var row = Rect("Seg_" + label, parent);
            Layout(row, label != null ? height + 60 : height);
            float top = 0;
            if (label != null)
            {
                var text = Label(row, label, 40, UITheme.TextDim, TextAnchor.UpperLeft);
                var trt = text.rectTransform;
                trt.anchorMin = new Vector2(0, 1); trt.anchorMax = new Vector2(1, 1); trt.pivot = new Vector2(0.5f, 1);
                trt.sizeDelta = new Vector2(0, 56); trt.anchoredPosition = Vector2.zero;
                top = 60;
            }
            var group = Rect("Options", row);
            Stretch(group, 0, 0, top, 0);
            HorizontalLayout(group, 14);
            var buttons = new List<Button>();
            for (int i = 0; i < options.Length; i++)
            {
                int index = i;
                var b = Button(group, options[i], UITheme.PanelLight, null, 38);
                buttons.Add(b);
                b.onClick.AddListener(() =>
                {
                    for (int k = 0; k < buttons.Count; k++) buttons[k].image.color = k == index ? UITheme.Primary : UITheme.PanelLight;
                    onSelect?.Invoke(index);
                });
                b.image.color = i == selected ? UITheme.Primary : UITheme.PanelLight;
            }
            return buttons;
        }

        /// <summary>ON/OFF toggle row.</summary>
        public static Button ToggleRow(Transform parent, string label, Func<bool> get, Action<bool> set)
        {
            var row = Rect("Toggle_" + label, parent);
            Layout(row, 110);
            var text = Label(row, label, 40, UITheme.Text, TextAnchor.MiddleLeft);
            var trt = text.rectTransform;
            trt.anchorMin = new Vector2(0, 0); trt.anchorMax = new Vector2(0.6f, 1); trt.offsetMin = trt.offsetMax = Vector2.zero;
            var b = Button(row, get() ? "ON" : "OFF", get() ? UITheme.Good : UITheme.PanelLight, null, 40);
            var brt = (RectTransform)b.transform;
            brt.anchorMin = new Vector2(0.62f, 0.08f); brt.anchorMax = new Vector2(1f, 0.92f); brt.offsetMin = brt.offsetMax = Vector2.zero;
            b.onClick.AddListener(() =>
            {
                bool v = !get();
                set(v);
                ButtonLabel(b).text = v ? "ON" : "OFF";
                b.image.color = v ? UITheme.Good : UITheme.PanelLight;
            });
            return b;
        }

        /// <summary>Full-screen dimmed overlay with a centred card; returns the card's content rect.</summary>
        public static RectTransform Modal(Transform parent, string title, out GameObject root, float width = 900, float height = 1300)
        {
            var overlay = Panel(parent, UITheme.Overlay, 0, "Modal_" + title);
            Stretch(overlay.rectTransform);
            root = overlay.gameObject;
            var card = Panel(overlay.transform, UITheme.Panel, 40, "Card");
            Place(card.rectTransform, new Vector2(0.5f, 0.5f), new Vector2(width, height), Vector2.zero);
            card.gameObject.AddComponent<FitInsideParent>();
            var titleText = Label(card.transform, title, 72, UITheme.Text);
            var trt = titleText.rectTransform;
            trt.anchorMin = new Vector2(0, 1); trt.anchorMax = new Vector2(1, 1); trt.pivot = new Vector2(0.5f, 1);
            trt.sizeDelta = new Vector2(-40, 120); trt.anchoredPosition = new Vector2(0, -30);
            var content = Rect("Content", card.transform);
            Stretch(content, 50, 50, 170, 50);
            return content;
        }

        /// <summary>Scrollable vertical list inside <paramref name="parent"/>; returns the content rect to fill.</summary>
        public static RectTransform ScrollList(RectTransform parent, float spacing)
        {
            var viewport = Rect("Viewport", parent);
            Stretch(viewport);
            viewport.gameObject.AddComponent<RectMask2D>();
            var vpImg = viewport.gameObject.AddComponent<Image>();
            vpImg.color = new Color(0, 0, 0, 0.001f);
            var content = Rect("Content", viewport);
            content.anchorMin = new Vector2(0, 1); content.anchorMax = new Vector2(1, 1); content.pivot = new Vector2(0.5f, 1);
            content.sizeDelta = new Vector2(0, 0);
            VerticalLayout(content, spacing);
            var fitter = content.gameObject.AddComponent<ContentSizeFitter>();
            fitter.verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            var scroll = parent.gameObject.AddComponent<ScrollRect>();
            scroll.viewport = viewport;
            scroll.content = content;
            scroll.horizontal = false;
            scroll.movementType = ScrollRect.MovementType.Elastic;
            scroll.scrollSensitivity = 30f;
            return content;
        }
    }
}
