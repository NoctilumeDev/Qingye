Component({
  properties: { images: Array, height: { type: Number, value: 515 }, active: { type: Boolean, value: true } },
  data: { current: 0, playing: true, width: 0, visible: true },
  lifetimes: { ready() { this.measure() } },
  pageLifetimes: { show() { this.setData({ visible: true }); this.measure() }, hide() { this.setData({ visible: false }) }, resize() { this.measure() } },
  methods: {
    measure() { this.createSelectorQuery().select('.hero-carousel').boundingClientRect(rect => { if (rect && rect.width) this.setData({ width: rect.width }) }).exec() },
    pause() { this.setData({ playing: false }) },
    change(e) { this.setData({ current: e.detail.current }) }
  }
})
