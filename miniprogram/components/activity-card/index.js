Component({ properties: { item: Object }, methods: { open() { this.triggerEvent('open', { id: this.data.item.id }) } } })
