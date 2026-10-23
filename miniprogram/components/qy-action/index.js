Component({
  properties: {
    variant: { type: String, value: 'primary' },
    size: { type: String, value: 'block' },
    loading: { type: Boolean, value: false },
    disabled: { type: Boolean, value: false },
    label: { type: String, value: '' }
  },
  methods: {
    activate() {
      if (this.data.disabled || this.data.loading) return
      this.triggerEvent('activate')
    }
  }
})
