
trait T {
  /**
   * @todo implement me
   */
  def foo(): Unit
}

abstract class C extends T {
  override def foo(): Unit = {}

  /*s*/foo/*e*/()
}
