// SPDX-License-Identifier: Apache-2.0
// Experimental binding, adapted from MMTk's Apache-2.0 DummyVM trait skeletons.
// Upstream: mmtk/mmtk-core docs/dummyvm/src, revision
// d154f4f0dcca27439b920165ce5c948ca18aef24. Modified for fixed 64-byte objects,
// two exact reference fields, explicit roots, and blocking manual collection.
// No Ironwood compiler, memory model, or native-root implementation is changed.

use mmtk::{MMTK, MMTKBuilder, Mutator, AllocationSemantics};
use mmtk::memory_manager as mm;
use mmtk::util::{Address, ObjectReference};
use mmtk::util::opaque_pointer::*;
use mmtk::util::copy::{CopySemantics, GCWorkerCopyContext};
use mmtk::vm::*;
use mmtk::vm::slot::{SimpleSlot, UnimplementedMemorySlice};
use std::sync::{OnceLock, Mutex, Condvar};
use std::sync::atomic::{AtomicUsize, Ordering};

#[derive(Default)]
pub struct ProbeVM;
pub struct Model;
pub struct Scan;
pub struct Collect;
pub struct Active;
pub struct References;

impl VMBinding for ProbeVM {
    type VMObjectModel = Model;
    type VMScanning = Scan;
    type VMCollection = Collect;
    type VMActivePlan = Active;
    type VMReferenceGlue = References;
    type VMSlot = SimpleSlot;
    type VMMemorySlice = UnimplementedMemorySlice;
    const MAX_ALIGNMENT: usize = 64;
}

static INSTANCE: OnceLock<Box<MMTK<ProbeVM>>> = OnceLock::new();
static MUTATOR: AtomicUsize = AtomicUsize::new(0);
static ROOT_BASE: AtomicUsize = AtomicUsize::new(0);
static ROOT_COUNT: AtomicUsize = AtomicUsize::new(0);
// (mutator parked, collection completed). Used only during collection.
static PARK: (Mutex<(bool, bool)>, Condvar) = (Mutex::new((false, false)), Condvar::new());
static COLLECTIONS: AtomicUsize = AtomicUsize::new(0);

fn instance() -> &'static MMTK<ProbeVM> { INSTANCE.get().unwrap() }
fn thread() -> VMMutatorThread {
    VMMutatorThread(VMThread(OpaquePointer::from_address(unsafe { Address::from_usize(1) })))
}
unsafe fn mutator() -> &'static mut Mutator<ProbeVM> {
    &mut *(MUTATOR.load(Ordering::Relaxed) as *mut Mutator<ProbeVM>)
}

impl Collection<ProbeVM> for Collect {
    fn stop_all_mutators<F>(_tls: VMWorkerThread, mut visit: F)
    where F: FnMut(&'static mut Mutator<ProbeVM>) {
        let mut state = PARK.0.lock().unwrap();
        while !state.0 { state = PARK.1.wait(state).unwrap(); }
        unsafe { visit(mutator()); }
    }
    fn resume_mutators(_tls: VMWorkerThread) {
        let mut state = PARK.0.lock().unwrap();
        state.1 = true;
        COLLECTIONS.fetch_add(1, Ordering::Relaxed);
        PARK.1.notify_all();
    }
    fn block_for_gc(_tls: VMMutatorThread) {
        let mut state = PARK.0.lock().unwrap();
        state.0 = true;
        PARK.1.notify_all();
        while !state.1 { state = PARK.1.wait(state).unwrap(); }
    }
    fn spawn_gc_thread(_tls: VMThread, ctx: GCThreadContext<ProbeVM>) {
        std::thread::spawn(move || {
            let GCThreadContext::Worker(worker) = ctx;
            let tls = VMWorkerThread(VMThread(OpaquePointer::from_address(unsafe { Address::from_usize(2) })));
            mm::start_worker(instance(), tls, worker);
        });
    }
}

impl ActivePlan<ProbeVM> for Active {
    fn number_of_mutators() -> usize { 1 }
    fn is_mutator(tls: VMThread) -> bool { tls == thread().0 }
    fn mutator(_tls: VMMutatorThread) -> &'static mut Mutator<ProbeVM> { unsafe { mutator() } }
    fn mutators<'a>() -> Box<dyn Iterator<Item=&'a mut Mutator<ProbeVM>> + 'a> {
        Box::new(std::iter::once(unsafe { mutator() }))
    }
}

impl Scanning<ProbeVM> for Scan {
    fn scan_roots_in_mutator_thread(_tls: VMWorkerThread, _mutator: &'static mut Mutator<ProbeVM>, _factory: impl RootsWorkFactory<SimpleSlot>) {}
    fn scan_vm_specific_roots(_tls: VMWorkerThread, mut factory: impl RootsWorkFactory<SimpleSlot>) {
        let base = ROOT_BASE.load(Ordering::Relaxed);
        let count = ROOT_COUNT.load(Ordering::Relaxed);
        let slots = (0..count).map(|i| SimpleSlot::from_address(unsafe { Address::from_usize(base + i * 8) })).collect();
        factory.create_process_roots_work(slots);
    }
    fn scan_object(_tls: VMWorkerThread, object: ObjectReference, visitor: &mut impl SlotVisitor<SimpleSlot>) {
        let address = object.to_raw_address();
        visitor.visit_slot(SimpleSlot::from_address(address));
        visitor.visit_slot(SimpleSlot::from_address(address + 8usize));
    }
    fn notify_initial_thread_scan_complete(_partial: bool, _tls: VMWorkerThread) {}
    fn supports_return_barrier() -> bool { false }
    fn prepare_for_roots_re_scanning() {}
}

impl ObjectModel<ProbeVM> for Model {
    const GLOBAL_LOG_BIT_SPEC: VMGlobalLogBitSpec = VMGlobalLogBitSpec::side_first();
    const GLOBAL_FIELD_UNLOG_BIT_SPEC: VMGlobalFieldUnlogBitSpec = VMGlobalFieldUnlogBitSpec::side_after(Self::GLOBAL_LOG_BIT_SPEC.as_spec());
    const LOCAL_FORWARDING_POINTER_SPEC: VMLocalForwardingPointerSpec = VMLocalForwardingPointerSpec::in_header(0);
    const LOCAL_FORWARDING_BITS_SPEC: VMLocalForwardingBitsSpec = VMLocalForwardingBitsSpec::side_first();
    const LOCAL_MARK_BIT_SPEC: VMLocalMarkBitSpec = VMLocalMarkBitSpec::side_after(Self::LOCAL_FORWARDING_BITS_SPEC.as_spec());
    const LOCAL_LOS_MARK_NURSERY_SPEC: VMLocalLOSMarkNurserySpec = VMLocalLOSMarkNurserySpec::side_after(Self::LOCAL_MARK_BIT_SPEC.as_spec());
    const OBJECT_REF_OFFSET_LOWER_BOUND: isize = 0;
    fn copy(_from: ObjectReference, _semantics: CopySemantics, _context: &mut GCWorkerCopyContext<ProbeVM>) -> ObjectReference { panic!("moving is disabled") }
    fn try_copy(_from: ObjectReference, _semantics: CopySemantics, _context: &mut GCWorkerCopyContext<ProbeVM>) -> Option<ObjectReference> { panic!("moving is disabled") }
    fn copy_to(_from: ObjectReference, _to: ObjectReference, _region: Address) -> Address { panic!("moving is disabled") }
    fn get_current_size(_object: ObjectReference) -> usize { 64 }
    fn get_size_when_copied(_object: ObjectReference) -> usize { 64 }
    fn get_align_when_copied(_object: ObjectReference) -> usize { 8 }
    fn get_align_offset_when_copied(_object: ObjectReference) -> usize { 0 }
    fn get_reference_when_copied_to(_from: ObjectReference, to: Address) -> ObjectReference { unsafe { ObjectReference::from_raw_address_unchecked(to) } }
    fn get_type_descriptor(_reference: ObjectReference) -> &'static [i8] { &[] }
    fn ref_to_object_start(object: ObjectReference) -> Address { object.to_raw_address() }
    fn ref_to_header(object: ObjectReference) -> Address { object.to_raw_address() }
    fn dump_object(object: ObjectReference) { eprintln!("{object:?}"); }
}

impl ReferenceGlue<ProbeVM> for References {
    type FinalizableType = ObjectReference;
    fn set_referent(_reference: ObjectReference, _referent: ObjectReference) { panic!("no weak-reference objects") }
    fn get_referent(_object: ObjectReference) -> Option<ObjectReference> { None }
    fn clear_referent(_object: ObjectReference) {}
    fn enqueue_references(_references: &[ObjectReference], _tls: VMWorkerThread) {}
}

#[no_mangle]
pub extern "C" fn mm_init(plan: i32) {
    let mut builder = MMTKBuilder::new();
    assert!(builder.set_option("plan", if plan == 0 { "MarkSweep" } else { "Immix" }));
    assert!(builder.set_option("threads", "1"));
    builder.options.gc_trigger.set(mmtk::util::options::GCTriggerSelector::FixedHeapSize(256 * 1024 * 1024));
    INSTANCE.set(mm::mmtk_init::<ProbeVM>(&builder)).unwrap_or_else(|_| panic!("duplicate initialization"));
    MUTATOR.store(Box::into_raw(mm::bind_mutator(instance(), thread())) as usize, Ordering::Relaxed);
    mm::initialize_collection(instance(), thread().0);
    mm::disable_collection(instance()).expect("disable collection");
}

#[no_mangle]
pub extern "C" fn mm_new_node() -> usize {
    unsafe {
        let p = mm::alloc(mutator(), 64, 8, 0, AllocationSemantics::Default);
        if p.is_zero() { return 0; }
        std::ptr::write_bytes(p.to_mut_ptr::<u8>(), 0, 64);
        mm::post_alloc(mutator(), ObjectReference::from_raw_address_unchecked(p), 64, AllocationSemantics::Default);
        p.as_usize()
    }
}

#[no_mangle]
pub extern "C" fn mm_set_roots(base: usize, count: usize) {
    ROOT_BASE.store(base, Ordering::Relaxed); ROOT_COUNT.store(count, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn mm_collect() {
    *PARK.0.lock().unwrap() = (false, false);
    mm::enable_collection(instance());
    assert!(mm::handle_user_collection_request(instance(), thread(), true));
    mm::disable_collection(instance()).expect("disable collection after request");
}

#[no_mangle]
pub extern "C" fn mm_used_bytes() -> usize { mm::used_bytes(instance()) }
#[no_mangle]
pub extern "C" fn mm_collections() -> usize { COLLECTIONS.load(Ordering::Relaxed) }
#[no_mangle]
pub extern "C" fn mm_is_live(address: usize) -> bool {
    unsafe { mm::is_live_object(ObjectReference::from_raw_address_unchecked(Address::from_usize(address))) }
}
