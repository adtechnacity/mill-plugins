package a

// Invoice rendering as written in module `a`. Module `b` carries a copy of the same
// block, so the duplication is only visible when both modules are scanned together.
object InvoiceReport:

  final case class Line(sku: String, quantity: Int, unitPrice: BigDecimal)

  def render(customer: String, lines: List[Line]): String =
    val subtotal = lines.map(line => line.unitPrice * line.quantity).sum
    val discount =
      if subtotal > 1000 then subtotal * BigDecimal("0.10")
      else if subtotal > 500 then subtotal * BigDecimal("0.05")
      else BigDecimal(0)
    val taxable  = subtotal - discount
    val tax      = taxable * BigDecimal("0.21")
    val total    = taxable + tax
    val header   = s"Invoice for $customer"
    val rows     = lines.zipWithIndex.map { case (line, index) =>
      val amount = line.unitPrice * line.quantity
      f"${index + 1}%3d. ${line.sku}%-12s x${line.quantity}%4d @ ${line.unitPrice}%10.2f = $amount%10.2f"
    }
    val footer   = List(
      f"Subtotal: $subtotal%10.2f",
      f"Discount: $discount%10.2f",
      f"Tax:      $tax%10.2f",
      f"Total:    $total%10.2f"
    )
    (header :: "=" * header.length :: rows).appendedAll("-" * 40 :: footer).mkString("\n")
