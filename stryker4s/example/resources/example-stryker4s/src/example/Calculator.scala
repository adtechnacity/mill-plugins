package example

object Calculator:
  def add(a: Int, b: Int): Int      = a + b
  def isPositive(n: Int): Boolean   = n > 0
  def clamp(n: Int, lo: Int, hi: Int): Int =
    if n < lo then lo else if n > hi then hi else n
