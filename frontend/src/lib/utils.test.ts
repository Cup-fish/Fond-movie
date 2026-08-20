import { describe, expect, it } from 'vitest'
import { formatCityList, formatDate } from './utils'

describe('formatDate', () => {
  it('returns yyyy-MM-dd pattern', () => {
    expect(formatDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

describe('formatCityList', () => {
  it('groups cities by pinyin first letter', () => {
    const list = [
      { id: 1, nm: '北京', py: 'beijing' },
      { id: 2, nm: '上海', py: 'shanghai' },
      { id: 3, nm: '广州', py: 'guangzhou' },
    ]
    const groups = formatCityList(list)

    expect(groups.length).toBe(3)
    expect(groups[0].tag).toBe('b')
    expect(groups[0].items[0].nm).toBe('北京')
  })
})
