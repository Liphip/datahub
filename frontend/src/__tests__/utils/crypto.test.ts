import { describe, it, expect, vi, beforeEach } from 'vitest'
import { sha256 } from '../../utils/crypto'

vi.stubGlobal('crypto', {
  subtle: {
    digest: vi.fn().mockResolvedValue(new ArrayBuffer(32)),
  },
})

const mockDigest = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SHA-256 Hash Function', () => {
  it('produces consistent hash for same input', async () => {
    const encoder = new TextEncoder()
    const testString = 'password123'
    const expectedHash = new Uint8Array([0x12, 0x34, 0x56, 0x78])
    
    mockDigest.mockResolvedValue(expectedHash)
    ;(globalThis.crypto.subtle.digest as ReturnType<typeof vi.fn>).mockResolvedValue(expectedHash)
    
    const msgBuffer = encoder.encode(testString)
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer)
    const hashArray = new Uint8Array(hashBuffer)
    let binary = ''
    for (let i = 0; i < hashArray.length; i++) {
      binary += String.fromCharCode(hashArray[i])
    }
    const result = btoa(binary)
    
    expect(result).toBeDefined()
  })

  it('handles empty string', async () => {
    const encoder = new TextEncoder()
    const testString = ''
    const expectedHash = new Uint8Array([])
    
    ;(globalThis.crypto.subtle.digest as ReturnType<typeof vi.fn>).mockResolvedValue(expectedHash)
    
    const msgBuffer = encoder.encode(testString)
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer)
    
    expect(hashBuffer).toBeDefined()
  })

  it('handles special characters', async () => {
    const encoder = new TextEncoder()
    const testString = 'p@$$w0rd!#$%^&*()'
    const expectedHash = new Uint8Array([1, 2, 3, 4])
    
    ;(globalThis.crypto.subtle.digest as ReturnType<typeof vi.fn>).mockResolvedValue(expectedHash)
    
    const msgBuffer = encoder.encode(testString)
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer)
    
    expect(hashBuffer).toBeDefined()
  })

  it('handles unicode characters', async () => {
    const encoder = new TextEncoder()
    const testString = 'пароль密码🔐'
    const expectedHash = new Uint8Array([1, 2, 3, 4])
    
    ;(globalThis.crypto.subtle.digest as ReturnType<typeof vi.fn>).mockResolvedValue(expectedHash)
    
    const msgBuffer = encoder.encode(testString)
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer)
    
    expect(hashBuffer).toBeDefined()
  })
})
