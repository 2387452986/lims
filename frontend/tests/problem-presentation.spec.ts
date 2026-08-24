import { describe, expect, it } from 'vitest'
import { ApiError, errorMessage } from '../src/api'
import { failureReasonLabel, problemMessage } from '../src/presentation'

describe('problem details Chinese projection', () => {
  it('projects backend field validation instead of exposing English constraints', () => {
    expect(problemMessage(400, 'VALIDATION_FAILED', '请求字段校验失败', {
      fieldErrors: { password: 'size must be between 0 and 128', username: 'must not be blank' },
    })).toBe('请求字段校验失败：密码：长度应为 0–128 个字符；账号：不能为空')
  })

  it('projects internal blockers and unknown English failures to readable Chinese', () => {
    expect(problemMessage(422, 'BUSINESS_RULE_BLOCKED', 'Unprocessable Entity', {
      blockers: ['PERSON_NOT_QUALIFIED', '设备证书已过期'],
    })).toBe('当前业务条件尚未满足：人员资格不满足；设备证书已过期')
    expect(errorMessage(new TypeError('crypto.randomUUID is not a function'))).toBe('当前浏览器兼容性校验失败，请刷新页面后重试')
    expect(errorMessage(new Error('opaque internal failure'), '保存失败')).toBe('保存失败')
    expect(errorMessage(new ApiError(500, 'DOWNLOAD_FAILED', 'IllegalStateException'))).toBe('服务暂时不可用，请稍后重试')
  })

  it('normalizes localized Bean Validation messages', () => {
    expect(problemMessage(400, 'VALIDATION_FAILED', '请求字段校验失败', {
      fieldErrors: { password: '个数必须在0和128之间' },
    })).toBe('请求字段校验失败：密码：长度应为 0–128 个字符')
  })

  it('never exposes background job internals in visible failure fields', () => {
    expect(failureReasonLabel('could not determine data type of parameter $1', '导出作业失败')).toBe('导出作业失败')
    expect(failureReasonLabel('模板已被停用', '报告生成失败')).toBe('模板已被停用')
  })
})
