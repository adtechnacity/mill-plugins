package atn.mill

sealed trait WorkDone { self =>
  def value: Int
  def and(o: WorkDone) = WorkDone(self.value + o.value)
}

case object NotAThing extends WorkDone {
  def value = 0
}

case object WrotePreCommitHook extends WorkDone {
  def value = 1
}

case object WrotePrePushHook extends WorkDone {
  def value = 2
}

case object WrotePrepareCommitMsgHook extends WorkDone {
  def value = 4
}

case object WroteCommitHook extends WorkDone {
  def value = 8
}

object WorkDone {
  import upickle.default._

  def apply(v: Int) = new WorkDone {
    val value = v
  }

  implicit val workDoneReadWrite: ReadWriter[WorkDone] = readwriter[Int].bimap[WorkDone](_.value, apply(_))
}
