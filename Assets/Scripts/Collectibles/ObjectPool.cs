using System;
using System.Collections.Generic;
using UnityEngine;

namespace CubeSnake.Collectibles
{
    /// <summary>
    /// Simple component pool. Objects are created by a factory, deactivated on release and re-activated on get.
    /// Used for collectibles, chain cubes, floating texts and UI markers so gameplay never instantiates/destroys
    /// objects per frame.
    /// </summary>
    public sealed class ObjectPool<T> where T : Component
    {
        readonly Func<T> _factory;
        readonly Stack<T> _free = new Stack<T>();
        readonly Action<T> _onGet;
        readonly Action<T> _onRelease;
        readonly HashSet<T> _inUse = new HashSet<T>();

        public ObjectPool(Func<T> factory, int prewarm = 0, Action<T> onGet = null, Action<T> onRelease = null)
        {
            _factory = factory ?? throw new ArgumentNullException(nameof(factory));
            _onGet = onGet;
            _onRelease = onRelease;
            for (int i = 0; i < prewarm; i++)
            {
                var item = _factory();
                item.gameObject.SetActive(false);
                _free.Push(item);
            }
        }

        public int CountFree => _free.Count;
        public int CountInUse => _inUse.Count;

        public T Get()
        {
            T item = null;
            while (_free.Count > 0 && item == null) item = _free.Pop();
            if (item == null) item = _factory();
            _inUse.Add(item);
            item.gameObject.SetActive(true);
            _onGet?.Invoke(item);
            return item;
        }

        public void Release(T item)
        {
            if (item == null) return;
            // Releasing twice would hand the same object to two owners; ignore duplicates.
            if (!_inUse.Remove(item)) return;
            _onRelease?.Invoke(item);
            item.gameObject.SetActive(false);
            _free.Push(item);
        }

        public void ReleaseAll()
        {
            var copy = new List<T>(_inUse);
            foreach (var item in copy) Release(item);
        }
    }
}
