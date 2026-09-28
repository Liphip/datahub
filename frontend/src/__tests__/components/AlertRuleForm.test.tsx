import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import AlertRuleForm from '../../components/AlertRuleForm'
import { DEFAULT_RULES, validateAlertRule } from '../../lib/alertRules'

describe('validateAlertRule', () => {
  it('accepts the default rules', () => {
    Object.values(DEFAULT_RULES).forEach((rule) => expect(validateAlertRule(rule)).toBeNull())
  })

  it('reports missing required settings', () => {
    expect(validateAlertRule({ type: 'threshold', field: '', value: 1 })).toBe('Field is required')
    expect(validateAlertRule({ type: 'threshold', field: 'v' })).toBe('Value is required')
    expect(validateAlertRule({ type: 'rate_of_change', field: 'v' })).toBe('Max change is required')
    expect(validateAlertRule({ type: 'time_window', field: 'v', value: 1, windowSeconds: 0 })).toBe('Window must be greater than 0')
    expect(validateAlertRule({ type: 'absence' })).toBe('Timeout must be greater than 0')
  })
})

describe('AlertRuleForm', () => {
  it('switches to the settings of the selected rule type and keeps shared settings', () => {
    const onChange = vi.fn()
    render(<AlertRuleForm rule={{ ...DEFAULT_RULES.threshold, severity: 'critical', cooldownSeconds: 60 }} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Rule type'), { target: { value: 'absence' } })
    expect(onChange).toHaveBeenCalledWith({ ...DEFAULT_RULES.absence, severity: 'critical', cooldownSeconds: 60, title: undefined, seriesField: undefined })
  })

  it('stores numeric values as numbers and text values as text', () => {
    const onChange = vi.fn()
    render(<AlertRuleForm rule={DEFAULT_RULES.threshold} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: '42.5' } })
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ value: 42.5 }))
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: 'FAULT' } })
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ value: 'FAULT' }))
  })
})
