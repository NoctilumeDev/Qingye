Component({
  properties: { item: Object, variant: { type: String, value: 'row' } },
  data: { failed: false },
  observers: { item() { this.setData({ failed: false }) } },
  methods: {
    open() { this.triggerEvent('open', { id: this.data.item.id }) },
    imageError() { if (!this.data.failed) this.setData({ failed: true }) }
  }
})
