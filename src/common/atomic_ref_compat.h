/*
 * src/common/atomic_ref_compat.h — std::atomic_ref portability shim.
 *
 * NDK r27c's libc++ (LLVM 18) does not provide std::atomic_ref (upstream
 * libc++ only shipped it in LLVM 19). The kernel needs atomic references
 * for lock-free publication and CAS over plain (non-atomic) storage: the
 * pthread static-object initializers and the fiber state machine.
 *
 * On toolchains where std::atomic_ref exists (glibc libstdc++, newer
 * libc++), Common::atomic_ref is an alias of std::atomic_ref. Otherwise it
 * is a minimal implementation over the compiler's __atomic builtins, which
 * generate the same code sequences std::atomic_ref would (the builtins are
 * what libc++ itself uses internally).
 */

#pragma once

#include <atomic>

#if defined(__ANDROID__) && !defined(__GLIBC__)

namespace Common {

template <typename T>
class atomic_ref {
public:
	explicit atomic_ref(T& obj) noexcept : m_ptr(&obj) {}

	atomic_ref(const atomic_ref&) noexcept = default;
	atomic_ref& operator=(const atomic_ref&) = delete;

	T load(std::memory_order order = std::memory_order_seq_cst) const noexcept
	{
		return __atomic_load_n(m_ptr, static_cast<int>(order));
	}

	void store(T value, std::memory_order order = std::memory_order_seq_cst) const noexcept
	{
		__atomic_store_n(m_ptr, value, static_cast<int>(order));
	}

	bool compare_exchange_strong(T& expected, T desired, std::memory_order success,
	                             std::memory_order failure) const noexcept
	{
		return __atomic_compare_exchange_n(m_ptr, &expected, desired, /*weak=*/false,
		                                   static_cast<int>(success), static_cast<int>(failure));
	}

private:
	T* m_ptr;
};

} // namespace Common

#else // std::atomic_ref is available

namespace Common {

template <typename T>
using atomic_ref = std::atomic_ref<T>;

} // namespace Common

#endif
