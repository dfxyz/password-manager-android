package dfxyz.passwordmanager

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dfxyz.passwordmanager.databinding.FatalErrorFragmentBinding
import dfxyz.passwordmanager.databinding.MainActivityBinding
import dfxyz.passwordmanager.databinding.MainFragmentBinding
import dfxyz.passwordmanager.databinding.MasterPasswordDialogFragmentBinding
import dfxyz.passwordmanager.databinding.PasswordEntryViewHolderBinding
import dfxyz.passwordmanager.databinding.RemovePasswordEntryDialogFragmentBinding
import dfxyz.passwordmanager.databinding.UpdatePasswordEntryDialogFragmentBinding
import dfxyz.passwordmanager.databinding.ViewPasswordEntryDialogFragmentBinding

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.onCreateView().onSuccess {
            when (it) {
                CheckShowMasterPasswordDialogResult.IGNORE -> switchToMainFragment()
                CheckShowMasterPasswordDialogResult.SHOW_FOR_INITIALIZING -> showPasswordDialog("Input the master password to initialize data:")
                CheckShowMasterPasswordDialogResult.SHOW_FOR_LOADING -> showPasswordDialog("Input the master password to load encrypted data:")
            }
        }.onFailure {
            switchToFatalErrorFragment(it)
        }
    }

    fun switchToMainFragment() {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            add(binding.view.id, MainFragment())
        }
    }

    private fun showPasswordDialog(message: CharSequence) {
        MasterPasswordDialog(message).show(supportFragmentManager, null)
    }

    fun switchToFatalErrorFragment(throwable: Throwable) {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            add(binding.view.id, FatalErrorFragment(throwable))
        }
    }

    fun showSnackbar(message: CharSequence) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}

class MasterPasswordDialog(private val message: CharSequence) : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var binding: MasterPasswordDialogFragmentBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        binding = MasterPasswordDialogFragmentBinding.inflate(LayoutInflater.from(context))
        binding.message.text = message
        binding.enterButton.setOnClickListener(this::onClickEnterButton)

        return AlertDialog.Builder(context).run {
            setView(binding.root)
            create()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (dialog as AlertDialog).apply {
            setCanceledOnTouchOutside(false)
            setOnCancelListener {
                requireActivity().finish()
            }
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickEnterButton(view: View) {
        val masterPassword = binding.password.text
        if (masterPassword.length < MIN_MASTER_PASSWORD_LENGTH) {
            Snackbar.make(
                binding.root,
                "The master password is too short! (at least 4 characters)",
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        viewModel.onEnterMasterPasswordEnter(masterPassword).onSuccess { result ->
            when (result) {
                EnterMasterPasswordResult.OK -> {
                    dismiss()
                    (requireActivity() as MainActivity).switchToMainFragment()
                }
                EnterMasterPasswordResult.INVALID_MASTER_PASSWORD -> {
                    Snackbar.make(
                        binding.root,
                        "The master password is invalid!",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                EnterMasterPasswordResult.DATA_FILE_CORRUPTED -> {
                    dismiss()
                    (requireActivity() as MainActivity).switchToFatalErrorFragment(RuntimeException("data file is corrupted"))
                }
            }
        }.onFailure {
            dismiss()
            (requireActivity() as MainActivity).switchToFatalErrorFragment(it)
        }
    }
}

class MainFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var binding: MainFragmentBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = MainFragmentBinding.inflate(inflater, container, false)

        val passwordEntriesAdapter = PasswordEntriesAdapter()
        binding.passwordEntries.adapter = passwordEntriesAdapter
        viewModel.entryList.observe(this) {
            passwordEntriesAdapter.submitList(it)
        }

        binding.newEntryButton.setOnClickListener(this::onClickNewEntryButton)

        return binding.root
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickNewEntryButton(view: View) {
        UpdatePasswordEntryDialog().show(requireActivity().supportFragmentManager, null)
    }

    private fun onClickPasswordEntry(entryName: CharSequence) {
        viewModel.onClickPasswordEntry(entryName)?.also {
            ViewPasswordEntryDialog(it).show(requireActivity().supportFragmentManager, null)
        }
    }

    private inner class PasswordEntriesAdapter :
        ListAdapter<CharSequence, PasswordEntryViewHolder>(PasswordEntryDiffCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PasswordEntryViewHolder {
            val binding = PasswordEntryViewHolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return PasswordEntryViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PasswordEntryViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private object PasswordEntryDiffCallback : DiffUtil.ItemCallback<CharSequence>() {
        override fun areItemsTheSame(oldItem: CharSequence, newItem: CharSequence): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: CharSequence, newItem: CharSequence): Boolean {
            return oldItem.contentEquals(newItem)
        }
    }

    private inner class PasswordEntryViewHolder(private val binding: PasswordEntryViewHolderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.entry.setOnClickListener(this::onClick)
        }

        fun bind(entryName: CharSequence) {
            binding.entry.text = entryName
        }

        @Suppress("UNUSED_PARAMETER")
        private fun onClick(view: View) {
            val entryName = binding.entry.text
            if (entryName.isEmpty()) {
                return
            }
            onClickPasswordEntry(entryName)
        }
    }
}

class UpdatePasswordEntryDialog(private val entryInfo: PasswordEntryInfo? = null) : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var binding: UpdatePasswordEntryDialogFragmentBinding

    @SuppressLint("SetTextI18n")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        binding = UpdatePasswordEntryDialogFragmentBinding.inflate(LayoutInflater.from(context))

        if (entryInfo == null) {
            binding.title.text = "Create Entry"
            binding.password.setText(viewModel.onGeneratePassword(NormalPassword(DEFAULT_PASSWORD_LENGTH)))
        } else {
            binding.title.text = "Modify Entry"
            binding.entryName.apply {
                keyListener = null
                setText(entryInfo.entryName)
            }
            binding.password.setText(entryInfo.password)
        }

        ArrayAdapter(context, android.R.layout.simple_spinner_item, PASSWORD_TYPES).also {
            binding.passwordType.adapter = it
        }
        ArrayAdapter(context, android.R.layout.simple_spinner_item, PASSWORD_LENGTHS).also {
            binding.passwordLength.adapter = it
            binding.passwordLength.setSelection(DEFAULT_PASSWORD_LENGTH_POSITION)
        }

        binding.generatePasswordButton.setOnClickListener(this::onClickGeneratePasswordButton)
        binding.confirmButton.setOnClickListener(this::onClickConfirmPasswordButton)
        binding.cancelButton.setOnClickListener { dismiss() }

        return AlertDialog.Builder(context).run {
            setView(binding.root)
            create()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (dialog as AlertDialog).apply {
            setCanceledOnTouchOutside(false)
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickGeneratePasswordButton(view: View) {
        val passwordOption = binding.passwordType.selectedItem as PasswordOption
        val passwordLength = binding.passwordLength.selectedItem as Int
        binding.password.setText(viewModel.onGeneratePassword(passwordOption.new(passwordLength)))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickConfirmPasswordButton(view: View) {
        val entryName = binding.entryName.text.toString().trim()
        if (entryName.isEmpty()) {
            Snackbar.make(binding.root, "Entry name is blank!", Snackbar.LENGTH_SHORT).show()
            return
        }
        val password = binding.password.text
        if (password.length < MIN_PASSWORD_LENGTH) {
            Snackbar.make(binding.root, "Password is too short! (at least 6 characters)", Snackbar.LENGTH_SHORT).show()
            return
        }

        val result = viewModel.onUpdatePasswordEntry(entryName, password, entryInfo != null)
        dismiss()
        (requireActivity() as MainActivity).showSnackbar(
            if (result) {
                if (entryInfo == null) {
                    "Entry \"$entryName\" saved."
                } else {
                    "Entry \"${entryInfo.entryName}\" updated."
                }
            } else {
                if (entryInfo == null) {
                    "Entry \"$entryName\" already existed!"
                } else {
                    "Entry \"${entryInfo.entryName}\" already removed!"
                }
            }
        )
    }
}

class ViewPasswordEntryDialog(private val passwordEntryInfo: PasswordEntryInfo) : DialogFragment() {
    private lateinit var binding: ViewPasswordEntryDialogFragmentBinding

    @SuppressLint("SetTextI18n")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        binding = ViewPasswordEntryDialogFragmentBinding.inflate(LayoutInflater.from(context))
        binding.entryName.text = "Entry: ${passwordEntryInfo.entryName}"
        binding.password.text = passwordEntryInfo.password
        binding.modifyButton.setOnClickListener(this::onClickModifyButton)
        binding.removeButton.setOnClickListener(this::onClickRemoveButton)
        binding.closeButton.setOnClickListener { dismiss() }

        return AlertDialog.Builder(context).run {
            setView(binding.root)
            create()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (dialog as AlertDialog).apply {
            setCanceledOnTouchOutside(false)
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickModifyButton(view: View) {
        dismiss()
        UpdatePasswordEntryDialog(passwordEntryInfo).show(requireActivity().supportFragmentManager, null)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickRemoveButton(view: View) {
        dismiss()
        RemovePasswordEntryDialog(passwordEntryInfo.entryName).show(requireActivity().supportFragmentManager, null)
    }
}

class RemovePasswordEntryDialog(private val entryName: CharSequence) : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    @SuppressLint("SetTextI18n")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val binding = RemovePasswordEntryDialogFragmentBinding.inflate(LayoutInflater.from(context))
        binding.message.text = "Are you sure to remove entry \"$entryName\"?"
        binding.removeButton.setOnClickListener(this::onClickRemoveButton)
        binding.cancelButton.setOnClickListener { dismiss() }

        return AlertDialog.Builder(context).run {
            setView(binding.root)
            create()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (dialog as AlertDialog).apply {
            setCanceledOnTouchOutside(false)
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClickRemoveButton(view: View) {
        val result = viewModel.onRemovePasswordEntry(entryName)
        dismiss()
        (requireActivity() as MainActivity).showSnackbar(
            if (result) {
                "Entry \"$entryName\" removed."
            } else {
                "Failed to remove entry \"$entryName\"!"
            }
        )
    }
}

class FatalErrorFragment(private val throwable: Throwable) : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FatalErrorFragmentBinding.inflate(inflater, container, false)
        binding.content.text = throwable.stackTraceToString()
        binding.content.movementMethod = ScrollingMovementMethod()
        return binding.root
    }
}
